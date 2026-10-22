package com.qabt.eztube.movie

import com.qabt.eztube.youtube.MediaSummary
import java.io.IOException
import java.text.Normalizer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MovieProviderRegistry(
    private val providers: List<MovieProvider>
) {
    init {
        require(providers.isNotEmpty()) { "MovieProviderRegistry requires at least one provider" }
    }

    private val searchExecutor = Executors.newFixedThreadPool(
        providers.size.coerceIn(1, 4)
    )

    private val failedProvidersByMediaId =
        java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    private val activeProviderByMediaId =
        java.util.concurrent.ConcurrentHashMap<String, String>()

    fun search(query: String): List<MediaSummary> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return emptyList()

        if (BILI_URL_REGEX.containsMatchIn(normalizedQuery)) {
            val bili = providers.firstOrNull { it.id == "biliintl" }
                ?: throw IOException("Bilibili provider is not installed")
            return bili.search(normalizedQuery).map { it.toMediaSummary() }
        }

        data class ProviderSearchResult(
            val providerName: String,
            val items: List<MovieCatalogItem> = emptyList(),
            val error: Throwable? = null
        )

        val futures = providers.map { provider ->
            provider to searchExecutor.submit(
                Callable {
                    runCatching { provider.search(normalizedQuery) }
                        .fold(
                            onSuccess = { ProviderSearchResult(provider.displayName, it) },
                            onFailure = { ProviderSearchResult(provider.displayName, error = it) }
                        )
                }
            )
        }

        val searchResults = futures.map { (provider, future) ->
            runCatching { future.get(8, TimeUnit.SECONDS) }
                .getOrElse {
                    future.cancel(true)
                    ProviderSearchResult(
                        providerName = provider.displayName,
                        error = IOException("${provider.displayName} search timed out", it)
                    )
                }
        }

        val candidates = searchResults.flatMap { it.items }
        if (candidates.isEmpty()) {
            val errors = searchResults.mapNotNull { result ->
                result.error?.message?.takeIf { it.isNotBlank() }?.let {
                    "${result.providerName}: $it"
                }
            }
            if (errors.isNotEmpty()) {
                throw IOException(errors.joinToString(" | "))
            }
        }

        return candidates
            .groupBy(::canonicalKey)
            .values
            .mapNotNull { sameMovie ->
                sameMovie.maxWithOrNull(
                    compareBy<MovieCatalogItem> { providerPreference(it) }
                        .thenBy { metadataScore(it) }
                )
            }
            .sortedWith(
                compareByDescending<MovieCatalogItem> { queryScore(normalizedQuery, it) }
                    .thenByDescending { it.year ?: 0 }
            )
            .take(24)
            .map { it.toMediaSummary() }
    }

    fun detail(
        mediaId: String,
        mediaTitle: String? = null
    ): MovieDetail {
        val parsed = parseTitleId(mediaId)
            ?: throw IOException("Invalid movie title id")
        val provider = providers.firstOrNull { it.id == parsed.first }
            ?: throw IOException("Movie provider not installed: ${parsed.first}")

        return provider.detail(parsed.second)
    }

    fun canResolve(mediaId: String): Boolean =
        mediaId.startsWith("movie:") &&
            providers.any { it.canResolve(mediaId) }

    fun resolve(
        mediaId: String,
        mediaTitle: String? = null
    ): MoviePlayback {
        val provider = providers.firstOrNull { it.canResolve(mediaId) }
            ?: throw IOException("No movie provider can resolve this item")
        return provider.resolve(mediaId)
    }

    fun nartoWatchPageUrl(mediaId: String): String? {
        val provider = providers
            .firstOrNull { it.id == "narto" } as? NartoDramaMovieProvider
            ?: return null
        return provider.watchPageUrl(mediaId)
    }

    fun diagnoseNarto(query: String): String {
        val provider = providers
            .firstOrNull { it.id == "narto" } as? NartoDramaMovieProvider
            ?: return "Narto provider is not installed"
        return provider.diagnostics(query)
    }

    fun reportPlaybackFailure(mediaId: String) {
        // Diagnostic build: keep MotPhim isolated and let Media3 retry the same
        // exact source. Do not redirect to another provider.
    }

    fun reportPlaybackHealthy(mediaId: String) {
        // No provider blacklist state in diagnostic mode.
    }

    fun isTitle(mediaId: String): Boolean = mediaId.startsWith("movie-title:")

    private fun parseTitleId(mediaId: String): Pair<String, String>? {
        val parts = mediaId.split(':', limit = 3)
        if (parts.size != 3 || parts[0] != "movie-title") return null
        if (parts[1].isBlank() || parts[2].isBlank()) return null
        return parts[1] to parts[2]
    }

    private fun canonicalKey(item: MovieCatalogItem): String =
        normalizeTitle(item.title)

    private fun providerPreference(item: MovieCatalogItem): Int =
        when (item.providerId) {
            // Direct Bilibili URLs are explicit user input and should win for that item.
            "biliintl" -> 50
            "phimapi" -> 50
            "nguonc" -> 45
            "tophim" -> 40
            "narto" -> 30
            "motphim" -> 20
            else -> 0
        }

    private fun metadataScore(item: MovieCatalogItem): Int =
        listOf(
            item.thumbnailUrl,
            item.originalTitle,
            item.language,
            item.durationText,
            item.episodeBadge
        ).count { !it.isNullOrBlank() } + if (item.isSeries != null) 2 else 0

    private fun queryScore(query: String, item: MovieCatalogItem): Int {
        val q = normalizeTitle(query)
        val title = normalizeTitle(item.title)
        val original = normalizeTitle(item.originalTitle.orEmpty())
        val haystack = "$title $original".trim()
        if (q.isBlank() || haystack.isBlank()) return 0
        if (title == q || original == q) return 1000
        if (title.startsWith(q) || q.startsWith(title)) return 750
        if (haystack.contains(q)) return 650

        val queryTokens = q.split(' ').filter { it.length >= 2 }
        val words = haystack.split(' ')
        val matched = queryTokens.count { token ->
            words.any { word ->
                word == token ||
                    (token.length >= 3 && word.startsWith(token)) ||
                    (word.length >= 3 && token.startsWith(word))
            }
        }
        return matched * 100
    }

    private fun normalizeTitle(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
        return normalized
            .replace(Regex("""\p{M}+"""), "")
            .replace('đ', 'd')
            .replace('Đ', 'D')
            .lowercase()
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")
    }

    companion object {
        private val BILI_URL_REGEX = Regex(
            """https?://(?:www\.)?(?:bilibili\.tv|biliintl\.com)/(?:[a-zA-Z]{2}/)?video/\d+""",
            RegexOption.IGNORE_CASE
        )

        fun default(): MovieProviderRegistry =
            MovieProviderRegistry(
                providers = listOf(
                    PhimApiMovieProvider(),
                    NguonCMovieProvider(),
                    TophimMovieProvider(),
                    NartoDramaMovieProvider(),
                    BiliIntlMovieProvider()
                )
            )
    }
}
