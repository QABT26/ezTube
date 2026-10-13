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

    fun detail(mediaId: String): MovieDetail {
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
        val primary = providers.firstOrNull { it.canResolve(mediaId) }
            ?: throw IOException("No movie provider can resolve this item")

        val failed = failedProvidersByMediaId
            .computeIfAbsent(mediaId) { java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }

        var lastError: Throwable? = null

        if (primary.id !in failed) {
            val direct = runCatching { primary.resolve(mediaId) }
            if (direct.isSuccess) {
                activeProviderByMediaId[mediaId] = primary.id
                return direct.getOrThrow()
            }
            lastError = direct.exceptionOrNull()
            failed += primary.id
        }

        val fallback = resolveFromAlternateProviders(
            mediaId = mediaId,
            mediaTitle = mediaTitle,
            excludedProviderIds = failed + primary.id
        )
        if (fallback.isSuccess) {
            val playback = fallback.getOrThrow()
            parseEpisodeProviderId(playback.mediaId)?.let {
                activeProviderByMediaId[mediaId] = it
            }
            return playback
        }
        lastError = fallback.exceptionOrNull() ?: lastError

        throw IOException(
            lastError?.message ?: "No playable movie source is available",
            lastError
        )
    }

    fun reportPlaybackFailure(mediaId: String) {
        val providerId = activeProviderByMediaId[mediaId]
            ?: parseEpisodeProviderId(mediaId)
            ?: return
        failedProvidersByMediaId
            .computeIfAbsent(mediaId) { java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }
            .add(providerId)
    }

    fun reportPlaybackHealthy(mediaId: String) {
        failedProvidersByMediaId.remove(mediaId)
    }

    private fun resolveFromAlternateProviders(
        mediaId: String,
        mediaTitle: String?,
        excludedProviderIds: Set<String>
    ): Result<MoviePlayback> = runCatching {
        val movieTitle = logicalMovieTitle(mediaTitle)
        if (movieTitle.isBlank()) {
            throw IOException("Cannot identify movie title for source fallback")
        }

        val episodeHint = logicalEpisodeHint(mediaTitle)
        var lastError: Throwable? = null

        val alternates = providers
            .filterNot { it.id in excludedProviderIds }
            .sortedByDescending { provider ->
                when (provider.id) {
                    "ophim" -> 40
                    "phimapi" -> 30
                    "motphim" -> 20
                    else -> 0
                }
            }

        for (provider in alternates) {
            val searchResult = runCatching { provider.search(movieTitle) }
            if (searchResult.isFailure) {
                lastError = searchResult.exceptionOrNull()
                continue
            }

            val candidates = searchResult.getOrThrow()
                .sortedByDescending { queryScore(movieTitle, it) }

            for (candidate in candidates.take(4)) {
                if (queryScore(movieTitle, candidate) < 600) continue

                val detailResult = runCatching { provider.detail(candidate.slug) }
                if (detailResult.isFailure) {
                    lastError = detailResult.exceptionOrNull()
                    continue
                }

                val episode = selectEquivalentEpisode(
                    detailResult.getOrThrow().episodes,
                    episodeHint
                ) ?: continue

                val playbackResult = runCatching { provider.resolve(episode.stableId) }
                if (playbackResult.isSuccess) {
                    return@runCatching playbackResult.getOrThrow()
                }
                lastError = playbackResult.exceptionOrNull()
            }
        }

        throw IOException(
            lastError?.message ?: "No alternate provider has a playable source",
            lastError
        )
    }

    private fun selectEquivalentEpisode(
        episodes: List<MovieEpisodeRef>,
        hint: String?
    ): MovieEpisodeRef? {
        if (episodes.isEmpty()) return null
        if (episodes.size == 1) return episodes.first()

        val normalizedHint = normalizeTitle(hint.orEmpty())
        if (normalizedHint.isBlank() || normalizedHint == "full") {
            return episodes.firstOrNull {
                normalizeTitle(it.episodeName) == "full" ||
                    normalizeTitle(it.episodeSlug) == "full"
            } ?: episodes.first()
        }

        val exact = episodes.firstOrNull {
            normalizeTitle(it.episodeName) == normalizedHint ||
                normalizeTitle(it.episodeSlug) == normalizedHint
        }
        if (exact != null) return exact

        val episodeNumber = Regex("""\d+""")
            .find(normalizedHint)
            ?.value
        if (episodeNumber != null) {
            return episodes.firstOrNull {
                Regex("""\d+""")
                    .find(normalizeTitle(it.episodeName + " " + it.episodeSlug))
                    ?.value == episodeNumber
            }
        }

        return null
    }

    private fun logicalMovieTitle(mediaTitle: String?): String =
        mediaTitle
            .orEmpty()
            .substringBefore(" · ")
            .trim()

    private fun logicalEpisodeHint(mediaTitle: String?): String? =
        mediaTitle
            ?.substringAfter(" · ", "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun parseEpisodeProviderId(mediaId: String): String? {
        val parts = mediaId.split(':', limit = 3)
        if (parts.size < 3 || parts[0] != "movie") return null
        return parts[1].takeIf { it.isNotBlank() }
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
            // Prefer providers that expose direct HLS over embed-first sources.
            "ophim" -> 40
            "phimapi" -> 30
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
        fun default(): MovieProviderRegistry =
            MovieProviderRegistry(
                providers = listOf(
                    OPhimMovieProvider(),
                    PhimApiMovieProvider(),
                    MotPhimMovieProvider()
                )
            )
    }
}
