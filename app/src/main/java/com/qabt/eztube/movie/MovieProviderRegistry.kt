package com.qabt.eztube.movie

import com.qabt.eztube.youtube.MediaSummary
import java.io.IOException
import java.text.Normalizer
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min

data class MovieProviderHealth(
    val providerId: String,
    val providerName: String,
    val consecutiveFailures: Int,
    val successes: Long,
    val failures: Long,
    val lastLatencyMs: Long?,
    val cooldownRemainingMs: Long,
    val lastError: String?
) {
    val available: Boolean
        get() = cooldownRemainingMs <= 0L
}

class MovieProviderRegistry(
    private val providers: List<MovieProvider>,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    init {
        require(providers.isNotEmpty()) { "MovieProviderRegistry requires at least one provider" }
        require(providers.map { it.id }.distinct().size == providers.size) {
            "MovieProviderRegistry provider ids must be unique"
        }
    }

    private data class ProviderRuntime(
        var consecutiveFailures: Int = 0,
        var successes: Long = 0,
        var failures: Long = 0,
        var lastLatencyMs: Long? = null,
        var cooldownUntilMs: Long = 0,
        var lastError: String? = null
    )

    private data class ProviderSearchResult(
        val provider: MovieProvider,
        val items: List<MovieCatalogItem> = emptyList(),
        val latencyMs: Long = 0,
        val error: Throwable? = null
    )

    private val searchExecutor = Executors.newFixedThreadPool(
        providers.size.coerceIn(1, 6)
    )
    private val runtimeByProviderId = ConcurrentHashMap<String, ProviderRuntime>()
    private val mirrorsByTitleId = ConcurrentHashMap<String, List<MovieCatalogItem>>()

    fun search(query: String): List<MediaSummary> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return emptyList()

        if (BILI_URL_REGEX.containsMatchIn(normalizedQuery)) {
            val bili = providers.firstOrNull { it.id == "biliintl" }
                ?: throw IOException("Bilibili provider is not installed")
            return runProviderCall(bili) { bili.search(normalizedQuery) }
                .map { it.toMediaSummary() }
        }

        val futures = providersForAttempt().map { provider ->
            provider to searchExecutor.submit(
                Callable {
                    val started = nowMs()
                    runCatching { provider.search(normalizedQuery) }
                        .fold(
                            onSuccess = {
                                ProviderSearchResult(
                                    provider = provider,
                                    items = it,
                                    latencyMs = (nowMs() - started).coerceAtLeast(0)
                                )
                            },
                            onFailure = {
                                ProviderSearchResult(
                                    provider = provider,
                                    latencyMs = (nowMs() - started).coerceAtLeast(0),
                                    error = it
                                )
                            }
                        )
                }
            )
        }

        val searchResults = futures.map { (provider, future) ->
            runCatching { future.get(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                .getOrElse { error ->
                    future.cancel(true)
                    ProviderSearchResult(
                        provider = provider,
                        error = IOException(provider.displayName + " search timed out", error)
                    )
                }
        }

        searchResults.forEach { result ->
            if (result.error == null) {
                recordSuccess(result.provider, result.latencyMs)
            } else {
                recordFailure(result.provider, result.latencyMs, result.error)
            }
        }

        val candidates = searchResults.flatMap { it.items }
        if (candidates.isEmpty()) {
            val errors = searchResults.mapNotNull { result ->
                result.error?.message?.takeIf { it.isNotBlank() }?.let {
                    result.provider.displayName + ": " + it
                }
            }
            if (errors.isNotEmpty()) throw IOException(errors.joinToString(" | "))
            return emptyList()
        }

        return buildCanonicalGroups(candidates)
            .mapNotNull { sameMovie ->
                val orderedMirrors = sameMovie.sortedWith(
                    compareByDescending<MovieCatalogItem> { candidateScore(it) }
                        .thenByDescending { metadataScore(it) }
                )
                val primary = orderedMirrors.firstOrNull() ?: return@mapNotNull null
                orderedMirrors.forEach { candidate ->
                    mirrorsByTitleId[candidate.stableId] = orderedMirrors
                }
                primary
            }
            .sortedWith(
                compareByDescending<MovieCatalogItem> { queryScore(normalizedQuery, it) }
                    .thenByDescending { candidateScore(it) }
                    .thenByDescending { it.year ?: 0 }
            )
            .take(MAX_SEARCH_RESULTS)
            .map { it.toMediaSummary() }
    }

    fun detail(
        mediaId: String,
        mediaTitle: String? = null
    ): MovieDetail {
        val parsed = parseTitleId(mediaId)
            ?: throw IOException("Invalid movie title id")
        val requestedProvider = providers.firstOrNull { it.id == parsed.first }
            ?: throw IOException("Movie provider not installed: " + parsed.first)

        val directCatalog = MovieCatalogItem(
            providerId = requestedProvider.id,
            providerName = requestedProvider.displayName,
            slug = parsed.second,
            title = mediaTitle.orEmpty().ifBlank { parsed.second.replace('-', ' ') },
            originalTitle = null,
            thumbnailUrl = null,
            year = null,
            language = null,
            durationText = null,
            durationSeconds = null
        )

        var knownMirrors = mirrorsByTitleId[mediaId].orEmpty()
        if (knownMirrors.isEmpty() && !mediaTitle.isNullOrBlank()) {
            // Library/history entries survive process restarts while the in-memory
            // mirror cache does not. Rebuild the mirror set from the saved title
            // before giving up on the originally selected provider.
            runCatching { search(mediaTitle) }
            knownMirrors = mirrorsByTitleId[mediaId].orEmpty()
        }

        val attempts = buildList {
            add(
                knownMirrors.firstOrNull {
                    it.providerId == parsed.first && it.slug == parsed.second
                } ?: directCatalog
            )
            knownMirrors
                .filterNot { it.providerId == parsed.first && it.slug == parsed.second }
                .sortedByDescending(::candidateScore)
                .forEach(::add)
        }.distinctBy { it.providerId + ":" + it.slug }

        var lastError: Throwable? = null
        for ((index, candidate) in attempts.withIndex()) {
            val provider = providers.firstOrNull { it.id == candidate.providerId } ?: continue
            if (isCoolingDown(provider.id) && index < attempts.lastIndex) continue

            val detail = runCatching {
                runProviderCall(provider) { provider.detail(candidate.slug) }
            }.onFailure { lastError = it }.getOrNull()

            if (detail != null) {
                if (knownMirrors.isNotEmpty()) {
                    mirrorsByTitleId[detail.catalog.stableId] = knownMirrors
                }
                return detail
            }
        }

        throw IOException(
            buildString {
                append("Unable to load movie detail")
                mediaTitle?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                lastError?.message?.takeIf { it.isNotBlank() }?.let {
                    append(" (").append(it).append(')')
                }
            },
            lastError
        )
    }

    fun canResolve(mediaId: String): Boolean =
        mediaId.startsWith("movie:") &&
            providers.any { it.canResolve(mediaId) }

    fun resolve(
        mediaId: String,
        mediaTitle: String? = null
    ): MoviePlayback {
        val parsedEpisode = parseEpisodeId(mediaId)
            ?: throw IOException("No movie provider can resolve this item")
        val requestedProvider = providers.firstOrNull { it.id == parsedEpisode.providerId }
            ?: throw IOException("Movie provider not installed: " + parsedEpisode.providerId)

        var lastError: Throwable? = null
        val direct = runCatching {
            runProviderCall(requestedProvider) { requestedProvider.resolve(mediaId) }
        }.onFailure { lastError = it }.getOrNull()
        if (direct != null) return direct

        val lookupTitle = mediaTitle
            ?.substringBefore(" · ")
            ?.trim()
            .orEmpty()
        if (lookupTitle.isBlank()) {
            throw IOException(
                "Unable to resolve movie playback from " + requestedProvider.displayName,
                lastError
            )
        }

        runCatching { search(lookupTitle) }

        val originalTitleId =
            "movie-title:" + parsedEpisode.providerId + ":" + parsedEpisode.movieSlug
        var mirrors = mirrorsByTitleId[originalTitleId].orEmpty()

        if (mirrors.isEmpty()) {
            // The original provider may have disappeared from current search results.
            // Reuse any canonical mirror group rebuilt by the title search.
            mirrors = mirrorsByTitleId.values
                .asSequence()
                .filter { group ->
                    group.any { candidate ->
                        normalizeTitle(candidate.title) == normalizeTitle(lookupTitle) ||
                            normalizeTitle(candidate.originalTitle.orEmpty()) ==
                                normalizeTitle(lookupTitle)
                    }
                }
                .flatten()
                .distinctBy { it.providerId + ":" + it.slug }
                .toList()
        }

        val primaryEpisode = runCatching {
            runProviderCall(requestedProvider) {
                requestedProvider.detail(parsedEpisode.movieSlug)
            }.episodes.firstOrNull { ref ->
                ref.stableId == mediaId ||
                    ref.episodeSlug.equals(parsedEpisode.episodeKey, ignoreCase = true)
            }
        }.getOrNull()

        val fallbackCandidates = mirrors
            .filterNot {
                it.providerId == parsedEpisode.providerId &&
                    it.slug == parsedEpisode.movieSlug
            }
            .sortedByDescending(::candidateScore)

        for ((index, candidate) in fallbackCandidates.withIndex()) {
            val provider = providers.firstOrNull { it.id == candidate.providerId } ?: continue
            if (isCoolingDown(provider.id) && index < fallbackCandidates.lastIndex) continue

            val detail = runCatching {
                runProviderCall(provider) { provider.detail(candidate.slug) }
            }.onFailure { lastError = it }.getOrNull() ?: continue

            val replacement = matchEpisode(
                original = primaryEpisode,
                parsed = parsedEpisode,
                candidates = detail.episodes
            ) ?: continue

            val playback = runCatching {
                runProviderCall(provider) { provider.resolve(replacement.stableId) }
            }.onFailure { lastError = it }.getOrNull()

            if (playback != null) return playback
        }

        throw IOException(
            buildString {
                append("Unable to resolve movie playback")
                append(": ").append(lookupTitle)
                lastError?.message?.takeIf { it.isNotBlank() }?.let {
                    append(" (").append(it).append(')')
                }
            },
            lastError
        )
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
        providers.firstOrNull { it.canResolve(mediaId) }?.let { provider ->
            recordFailure(provider, 0, IOException("Playback failed"))
        }
    }

    fun reportPlaybackHealthy(mediaId: String) {
        providers.firstOrNull { it.canResolve(mediaId) }?.let { provider ->
            recordSuccess(provider, 0)
        }
    }

    fun providerHealth(): List<MovieProviderHealth> =
        providers.map { provider ->
            val runtime = runtimeByProviderId[provider.id] ?: ProviderRuntime()
            MovieProviderHealth(
                providerId = provider.id,
                providerName = provider.displayName,
                consecutiveFailures = runtime.consecutiveFailures,
                successes = runtime.successes,
                failures = runtime.failures,
                lastLatencyMs = runtime.lastLatencyMs,
                cooldownRemainingMs = (runtime.cooldownUntilMs - nowMs()).coerceAtLeast(0),
                lastError = runtime.lastError
            )
        }.sortedByDescending { providerStaticPreference(it.providerId) }

    fun isTitle(mediaId: String): Boolean = mediaId.startsWith("movie-title:")

    private fun providersForAttempt(): List<MovieProvider> {
        val active = providers.filterNot { isCoolingDown(it.id) }
        return (if (active.isNotEmpty()) active else providers)
            .sortedByDescending { providerDynamicScore(it.id) }
    }

    private fun isCoolingDown(providerId: String): Boolean =
        (runtimeByProviderId[providerId]?.cooldownUntilMs ?: 0L) > nowMs()

    private fun <T> runProviderCall(provider: MovieProvider, block: () -> T): T {
        val started = nowMs()
        return try {
            block().also {
                recordSuccess(provider, (nowMs() - started).coerceAtLeast(0))
            }
        } catch (error: Throwable) {
            recordFailure(provider, (nowMs() - started).coerceAtLeast(0), error)
            throw error
        }
    }

    private fun recordSuccess(provider: MovieProvider, latencyMs: Long) {
        runtimeByProviderId.compute(provider.id) { _, existing ->
            val runtime = existing ?: ProviderRuntime()
            runtime.successes += 1
            runtime.consecutiveFailures = 0
            runtime.lastLatencyMs = latencyMs
            runtime.cooldownUntilMs = 0
            runtime.lastError = null
            runtime
        }
    }

    private fun recordFailure(
        provider: MovieProvider,
        latencyMs: Long,
        error: Throwable
    ) {
        runtimeByProviderId.compute(provider.id) { _, existing ->
            val runtime = existing ?: ProviderRuntime()
            runtime.failures += 1
            runtime.consecutiveFailures += 1
            runtime.lastLatencyMs = latencyMs
            runtime.lastError = error.message ?: error.javaClass.simpleName

            if (runtime.consecutiveFailures >= FAILURE_THRESHOLD) {
                val exponent =
                    (runtime.consecutiveFailures - FAILURE_THRESHOLD).coerceIn(0, 4)
                val cooldown = min(
                    MAX_COOLDOWN_MS,
                    BASE_COOLDOWN_MS * (1L shl exponent)
                )
                runtime.cooldownUntilMs = nowMs() + cooldown
            }
            runtime
        }
    }

    private fun candidateScore(item: MovieCatalogItem): Int =
        providerDynamicScore(item.providerId) + metadataScore(item)

    private fun providerDynamicScore(providerId: String): Int {
        val runtime = runtimeByProviderId[providerId]
        val healthAdjustment = when {
            runtime == null -> 0
            isCoolingDown(providerId) -> -120
            runtime.consecutiveFailures > 0 -> -25 * runtime.consecutiveFailures
            else -> 0
        }
        val reliabilityAdjustment = runtime?.let {
            val total = it.successes + it.failures
            if (total < 3L) {
                if (it.successes > 0L) 3 else 0
            } else {
                val successRatio = it.successes.toDouble() / total.toDouble()
                when {
                    successRatio >= 0.95 -> 18
                    successRatio >= 0.80 -> 10
                    successRatio >= 0.60 -> 2
                    successRatio >= 0.40 -> -10
                    else -> -25
                }
            }
        } ?: 0
        val latencyAdjustment = runtime?.lastLatencyMs?.let {
            when {
                it <= 350 -> 10
                it <= 800 -> 6
                it <= 1_500 -> 2
                it <= 3_000 -> 0
                it <= 5_000 -> -6
                else -> -12
            }
        } ?: 0
        return providerStaticPreference(providerId) +
            healthAdjustment +
            reliabilityAdjustment +
            latencyAdjustment
    }

    private fun providerStaticPreference(providerId: String): Int =
        when (providerId) {
            "biliintl" -> 70
            "phimapi" -> 65
            "ophim" -> 62
            "nguonc" -> 58
            "phim4k" -> 54
            "tophim" -> 50
            "narto" -> 35
            "motphim" -> 20
            else -> 10
        }

    private fun parseTitleId(mediaId: String): Pair<String, String>? {
        val parts = mediaId.split(':', limit = 3)
        if (parts.size != 3 || parts[0] != "movie-title") return null
        if (parts[1].isBlank() || parts[2].isBlank()) return null
        return parts[1] to parts[2]
    }
    private data class ParsedEpisodeId(
        val providerId: String,
        val movieSlug: String,
        val serverName: String,
        val episodeKey: String
    )

    private fun parseEpisodeId(mediaId: String): ParsedEpisodeId? {
        val parts = mediaId.split(':', limit = 5)
        if (parts.size != 5 || parts[0] != "movie") return null
        val providerId = parts[1].trim()
        val movieSlug = parts[2].trim()
        if (providerId.isBlank() || movieSlug.isBlank()) return null

        return ParsedEpisodeId(
            providerId = providerId,
            movieSlug = movieSlug,
            serverName = decodeStablePart(parts[3]),
            episodeKey = decodeStablePart(parts[4])
        )
    }

    private fun decodeStablePart(value: String): String =
        runCatching {
            String(
                java.util.Base64.getUrlDecoder().decode(value),
                Charsets.UTF_8
            )
        }.getOrDefault(value)

    private fun matchEpisode(
        original: MovieEpisodeRef?,
        parsed: ParsedEpisodeId,
        candidates: List<MovieEpisodeRef>
    ): MovieEpisodeRef? {
        if (candidates.isEmpty()) return null

        return candidates
            .map { candidate ->
                candidate to episodeMatchScore(
                    original = original,
                    parsed = parsed,
                    candidate = candidate
                )
            }
            .filter { (_, score) -> score >= MIN_EPISODE_MATCH_SCORE }
            .maxWithOrNull(
                compareBy<Pair<MovieEpisodeRef, Int>> { it.second }
                    .thenByDescending { it.first.episodeIndex }
            )
            ?.first
    }

    private fun episodeMatchScore(
        original: MovieEpisodeRef?,
        parsed: ParsedEpisodeId,
        candidate: MovieEpisodeRef
    ): Int {
        var score = 0

        val parsedKey = normalizeEpisodeKey(parsed.episodeKey)
        val candidateSlug = normalizeEpisodeKey(candidate.episodeSlug)
        val candidateName = normalizeEpisodeKey(candidate.episodeName)
        val candidateServer = normalizeEpisodeKey(candidate.serverName)

        val originalSlug = normalizeEpisodeKey(original?.episodeSlug.orEmpty())
        val originalName = normalizeEpisodeKey(original?.episodeName.orEmpty())
        val originalServer = normalizeEpisodeKey(original?.serverName.orEmpty())

        if (parsedKey.isNotBlank() && candidateSlug == parsedKey) score += 100
        if (originalSlug.isNotBlank() && candidateSlug == originalSlug) score += 100
        if (originalName.isNotBlank() && candidateName == originalName) score += 90

        val originalNumber = episodeNumber(
            original?.episodeName.orEmpty() + " " +
                original?.episodeSlug.orEmpty() + " " +
                parsed.episodeKey
        )
        val candidateNumber = episodeNumber(
            candidate.episodeName + " " + candidate.episodeSlug
        )
        if (originalNumber != null && candidateNumber == originalNumber) score += 80

        if (originalServer.isNotBlank() && candidateServer == originalServer) score += 15
        if (
            original == null &&
            normalizeEpisodeKey(parsed.serverName).isNotBlank() &&
            candidateServer == normalizeEpisodeKey(parsed.serverName)
        ) {
            score += 10
        }

        if (original != null && candidate.episodeIndex == original.episodeIndex) score += 20
        if (original?.language != null &&
            candidate.language?.equals(original.language, ignoreCase = true) == true
        ) {
            score += 5
        }

        return score
    }

    private fun normalizeEpisodeKey(value: String): String =
        normalizeTitle(value)
            .replace(Regex("""\b(tap|episode|ep|phan|part)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun episodeNumber(value: String): Int? =
        Regex("""(?:tap|episode|ep|e)?\s*(\d{1,4})""", RegexOption.IGNORE_CASE)
            .find(normalizeTitle(value))
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

    private fun buildCanonicalGroups(
        candidates: List<MovieCatalogItem>
    ): List<List<MovieCatalogItem>> {
        val groups = mutableListOf<MutableList<MovieCatalogItem>>()

        for (candidate in candidates) {
            val matching = groups.firstOrNull { group ->
                group.any { existing -> sameMovieIdentity(existing, candidate) }
            }
            if (matching != null) {
                matching += candidate
            } else {
                groups += mutableListOf(candidate)
            }
        }

        return groups
    }

    private fun sameMovieIdentity(
        left: MovieCatalogItem,
        right: MovieCatalogItem
    ): Boolean {
        if (
            left.isSeries != null &&
            right.isSeries != null &&
            left.isSeries != right.isSeries
        ) return false

        if (
            left.year != null &&
            right.year != null &&
            kotlin.math.abs(left.year - right.year) > 1
        ) return false

        val leftTitles = normalizedMovieTitles(left)
        val rightTitles = normalizedMovieTitles(right)

        if (leftTitles.any { it in rightTitles }) return true

        val bestOverlap = leftTitles.maxOfOrNull { a ->
            rightTitles.maxOfOrNull { b -> tokenSimilarity(a, b) } ?: 0.0
        } ?: 0.0

        return bestOverlap >= 0.82
    }

    private fun normalizedMovieTitles(item: MovieCatalogItem): Set<String> =
        buildSet {
            normalizeTitle(item.title).takeIf { it.isNotBlank() }?.let(::add)
            normalizeTitle(item.originalTitle.orEmpty())
                .takeIf { it.isNotBlank() }
                ?.let(::add)
        }

    private fun tokenSimilarity(left: String, right: String): Double {
        val a = left.split(' ').filter { it.length >= 2 }.toSet()
        val b = right.split(' ').filter { it.length >= 2 }.toSet()
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble()
        return if (union == 0.0) 0.0 else intersection / union
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
        private const val SEARCH_TIMEOUT_SECONDS = 8L
        private const val MAX_SEARCH_RESULTS = 24
        private const val FAILURE_THRESHOLD = 2
        private const val MIN_EPISODE_MATCH_SCORE = 70
        private const val BASE_COOLDOWN_MS = 15_000L
        private const val MAX_COOLDOWN_MS = 120_000L

        private val BILI_URL_REGEX = Regex(
            """https?://(?:www\.)?(?:bilibili\.tv|biliintl\.com)/(?:[a-zA-Z]{2}/)?video/\d+""",
            RegexOption.IGNORE_CASE
        )

        fun default(): MovieProviderRegistry =
            MovieProviderRegistry(
                providers = listOf(
                    PhimApiMovieProvider(),
                    OPhimMovieProvider(),
                    NguonCMovieProvider(),
                    Phim4KMovieProvider(),
                    TophimMovieProvider(),
                    NartoDramaMovieProvider(),
                    BiliIntlMovieProvider()
                )
            )
    }
}
