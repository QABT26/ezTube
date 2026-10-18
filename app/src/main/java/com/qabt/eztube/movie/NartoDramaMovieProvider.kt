package com.qabt.eztube.movie

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * Narto Drama adapter.
 *
 * It deliberately does not bypass Cloudflare, ad gates, or access-control
 * flows. Playback is accepted only when a signed stream URL is already present
 * in the public episode response returned to a normal HTTP client.
 *
 * Signed stream URLs are temporary, so only stable episode IDs are persisted.
 */
class NartoDramaMovieProvider(
    private val client: OkHttpClient? = null
) : MovieProvider {
    override val id = "narto"
    override val displayName = "Narto Drama"

    private val http = client ?: OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun search(query: String): List<MovieCatalogItem> {
        val normalized = query.trim()
        if (normalized.isBlank()) return emptyList()

        parseWatchUrl(normalized)?.first?.let { slug ->
            return runCatching { listOf(detail(slug).catalog) }
                .getOrDefault(emptyList())
        }

        val catalogPage = runCatching { fetchPage(catalogUrls()) }.getOrNull()
        if (catalogPage != null) {
            val hits = extractCatalogItems(catalogPage)
            val q = normalizeSearchText(normalized)
            val ranked = hits
                .filter {
                    val title = normalizeSearchText(it.title)
                    title.contains(q) || q.contains(title) ||
                        q.split(' ').filter { token -> token.length >= 2 }
                            .count { token -> title.contains(token) } >= 2
                }
                .sortedByDescending {
                    val title = normalizeSearchText(it.title)
                    when {
                        title == q -> 3
                        title.startsWith(q) || q.startsWith(title) -> 2
                        else -> 1
                    }
                }
                .take(8)
            if (ranked.isNotEmpty()) {
                return ranked.map { hydrateCatalogItem(it) }
            }
        }

        val slug = slugify(normalized).takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching { listOf(detail(slug).catalog) }
            .getOrDefault(emptyList())
    }

    private fun hydrateCatalogItem(item: MovieCatalogItem): MovieCatalogItem {
        if (!item.thumbnailUrl.isNullOrBlank() && item.episodeBadge != null) {
            return item
        }

        val page = runCatching { fetchPage(detailUrls(item.slug)) }.getOrNull()
            ?: return item

        val thumbnail = item.thumbnailUrl
            ?: extractMeta(page, "og:image")?.let(::normalizeImageUrl)
            ?: extractFirstImageUrl(page)

        val episodeCount = item.episodeBadge
            ?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
            ?: extractEpisodeCount(page, item.slug)

        val title = item.title.takeIf { it.isNotBlank() }
            ?: extractTitle(page)
            ?: item.title

        return item.copy(
            title = title,
            thumbnailUrl = thumbnail,
            episodeBadge = episodeCount?.let { it.toString() + " tập" } ?: item.episodeBadge,
            isSeries = episodeCount?.let { it > 1 } ?: item.isSeries
        )
    }

    override fun detail(slug: String): MovieDetail {
        requireSlug(slug)
        val page = runCatching { fetchPage(detailUrls(slug)) }.getOrNull()
        val catalogFallback = runCatching {
            extractCatalogItems(fetchPage(catalogUrls()))
                .firstOrNull { it.slug == slug }
        }.getOrNull()

        val title = catalogFallback?.title
            ?: page?.let(::extractTitle)
            ?: slug.replace('-', ' ').replaceFirstChar { it.uppercase() }
        val thumbnail = catalogFallback?.thumbnailUrl
            ?: page?.let { extractMeta(it, "og:image") }
                ?.let(::normalizeImageUrl)
        val episodeCount = page?.let { extractEpisodeCount(it, slug) }
            ?: catalogFallback?.episodeBadge
                ?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
            ?: throw IOException("Narto detail page has no episode list")

        val catalog = MovieCatalogItem(
            providerId = id,
            providerName = displayName,
            slug = slug,
            title = title,
            originalTitle = null,
            thumbnailUrl = thumbnail,
            year = null,
            language = "Tiếng Việt",
            durationText = null,
            durationSeconds = null,
            episodeBadge = episodeCount.toString() + " tập",
            isSeries = episodeCount > 1
        )

        val episodes = (1..episodeCount).map { episode ->
            MovieEpisodeRef(
                providerId = id,
                providerName = displayName,
                movieSlug = slug,
                movieTitle = title,
                serverIndex = 0,
                episodeIndex = episode - 1,
                serverName = "Narto",
                episodeSlug = "episode-" + episode,
                episodeName = "Tập " + episode,
                thumbnailUrl = thumbnail,
                durationSeconds = null,
                language = "Tiếng Việt"
            )
        }
        return MovieDetail(catalog, episodes)
    }

    fun diagnostics(query: String): String {
        val normalized = query.trim().ifBlank { "tôi có ba bạn gái" }
        val slug = parseWatchUrl(normalized)?.first
            ?: slugify(normalized).ifBlank { "toi-co-ba-ban-gai-nho-he-thong" }

        val urls = (catalogUrls() + watchUrls(slug, 1)).distinct()
        val lines = mutableListOf<String>()
        lines += "Narto diagnostics"
        lines += "Query: " + normalized
        lines += "Slug: " + slug
        lines += "Targets: " + urls.size

        urls.forEachIndexed { index, url ->
            val started = System.nanoTime()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
                .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
                .get()
                .build()

            runCatching {
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                    val finalUrl = response.request.url.toString()
                    val contentType = response.header("Content-Type").orEmpty()
                    val challenge = looksLikeChallenge(body, finalUrl, response.code)
                    val detailLinks = DETAIL_HREF_REGEX.findAll(
                        normalizeEmbeddedText(body)
                    ).count()
                    val parsedCards = runCatching {
                        extractCatalogItems(body).size
                    }.getOrDefault(-1)
                    val queryMatches = runCatching {
                        val q = normalizeSearchText(normalized)
                        extractCatalogItems(body).count {
                            val title = normalizeSearchText(it.title)
                            title.contains(q) || q.contains(title)
                        }
                    }.getOrDefault(-1)
                    val hasTargetSlug = body.contains(slug, ignoreCase = true)
                    val hasSignedHls = extractSignedHlsUrl(body) != null

                    lines += ""
                    lines += "[" + (index + 1) + "] " + url
                    lines += "HTTP: " + response.code + " · " + elapsedMs + " ms"
                    lines += "Final: " + finalUrl
                    lines += "Type: " + contentType.ifBlank { "(none)" }
                    lines += "Body: " + body.length + " chars"
                    lines += "Challenge: " + challenge
                    lines += "detail/watch links: " + detailLinks
                    lines += "Parsed cards: " + parsedCards
                    lines += "Query matches: " + queryMatches
                    lines += "Target slug present: " + hasTargetSlug
                    lines += "Signed HLS present: " + hasSignedHls
                }
            }.onFailure { error ->
                val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                lines += ""
                lines += "[" + (index + 1) + "] " + url
                lines += "ERROR after " + elapsedMs + " ms"
                lines += error.javaClass.simpleName + ": " +
                    (error.message ?: "unknown error")
            }
        }

        return lines.joinToString("\n")
    }

    override fun resolve(mediaId: String): MoviePlayback {
        val parsed = parseMediaId(mediaId)
            ?: throw IOException("Unsupported Narto media id")
        val pageUrls = watchUrls(parsed.first, parsed.second)
        val pageUrl = pageUrls.first()
        val page = fetchPage(pageUrls)

        val hls = extractSignedHlsUrl(page)
            ?: throw IOException(
                "Narto playback URL is not present in the public episode response. " +
                    "This build does not bypass the site's unlock or challenge flow."
            )

        val subtitles = extractSubtitleUrls(page).mapIndexed { index, url ->
            MovieSubtitle(
                language = if (index == 0) "vi" else null,
                label = if (index == 0) "Tiếng Việt" else "Subtitle " + (index + 1),
                url = url,
                mimeType = "text/vtt"
            )
        }

        return MoviePlayback(
            mediaId = mediaId,
            hlsUrl = hls,
            subtitles = subtitles,
            requestHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to pageUrl,
                "Origin" to WEB_BASE
            ),
            diagnostic = "Provider: Narto Drama\nEpisode: " + parsed.second +
                "\nPlayback: signed HLS\nSubtitles: " + subtitles.size
        )
    }

    private fun fetchPage(urls: List<String>): String {
        var lastError: Throwable? = null
        urls.forEach { url ->
            val result = runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
                    .get()
                    .build()

                http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IOException("Narto page HTTP " + response.code)
                    }
                    if (body.isBlank()) throw IOException("Narto returned empty page")
                    body
                }
            }
            if (result.isSuccess) return result.getOrThrow()
            lastError = result.exceptionOrNull()
        }
        throw IOException(lastError?.message ?: "Narto page unavailable", lastError)
    }

    private fun catalogUrls(): List<String> =
        WEB_BASES.flatMap { base ->
            listOf(
                base + "/?lang=vi-VN&tab-provider=bibishort",
                base + "/?category=urban&lang=vi-VN&tab-provider=bibishort"
            )
        }

    private fun detailUrls(slug: String) = WEB_BASES.map {
        // Narto's watch route is episode-scoped; episode 1 is the canonical
        // detail entry used to obtain title/poster/episode metadata.
        it + "/detail/watch/" + slug + "/1?lang=vi-VN"
    }

    private fun watchUrls(slug: String, episode: Int) = WEB_BASES.map {
        it + "/detail/watch/" + slug + "/" + episode + "?lang=vi-VN"
    }

    private fun requireSlug(value: String) {
        if (!SLUG_REGEX.matches(value)) throw IOException("Invalid Narto movie slug")
    }

    private fun parseMediaId(mediaId: String): Pair<String, Int>? {
        val parts = mediaId.split(':')
        if (parts.size != 5 || parts[0] != "movie" || parts[1] != id) return null
        val slug = parts[2]
        if (!SLUG_REGEX.matches(slug)) return null
        if (!decodeStablePart(parts[3]).equals("Narto", ignoreCase = true)) return null
        val episode = EPISODE_SLUG_REGEX.matchEntire(decodeStablePart(parts[4]))
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
            ?: return null
        return slug to episode
    }

    private fun decodeStablePart(value: String): String =
        runCatching {
            String(java.util.Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
        }.getOrElse { throw IOException("Invalid Narto stable id") }

    companion object {
        private const val WEB_BASE = "https://narto-drama.com"
        private val WEB_BASES = listOf(
            "https://edge.narto-drama.com",
            "https://narto-drama.com"
        )
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

        private val SLUG_REGEX = Regex("""[a-z0-9]+(?:-[a-z0-9]+)*""")
        private val EPISODE_SLUG_REGEX = Regex("""episode-(\d+)""")
        private val WATCH_URL_REGEX = Regex(
            """https?://(?:edge\.)?narto-drama\.com/detail/watch/([a-z0-9-]+)(?:/(\d+))?(?:[?#].*)?""",
            RegexOption.IGNORE_CASE
        )
        private val META_REGEX = Regex(
            """<meta\s+[^>]*(?:property|name)\s*=\s*["']([^"']+)["'][^>]*content\s*=\s*["']([^"']*)["'][^>]*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val META_REVERSED_REGEX = Regex(
            """<meta\s+[^>]*content\s*=\s*["']([^"']*)["'][^>]*(?:property|name)\s*=\s*["']([^"']+)["'][^>]*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val TITLE_REGEX = Regex(
            """<title[^>]*>(.*?)</title>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val ABSOLUTE_HLS_REGEX =
            Regex("""https://stream\.narto-drama\.com/e/m/[A-Za-z0-9._~-]+""")
        private val RELATIVE_HLS_REGEX =
            Regex("""(?<![A-Za-z0-9])(/e/m/[A-Za-z0-9._~-]+)""")
        private val SUBTITLE_REGEX =
            Regex("""https://narto-drama\.com/e/s/[A-Za-z0-9._~-]+""")


        private val CATALOG_ANCHOR_REGEX = Regex(
            """<a\b[^>]*href\s*=\s*["']([^"']*?/detail/watch/([a-z0-9-]+)(?:/\d+)?[^"']*)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val DETAIL_HREF_REGEX = Regex(
            """href\s*=\s*["']([^"']*?/detail/watch/([a-z0-9-]+)(?:/\d+)?[^"']*)["']""",
            RegexOption.IGNORE_CASE
        )
        private val HEADING_REGEX = Regex(
            """<h[1-6]\b[^>]*>(.*?)</h[1-6]>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val IMG_REGEX = Regex(
            """<img\b[^>]*(?:src|data-src)\s*=\s*["']([^"']+)["'][^>]*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val EP_COUNT_REGEX = Regex(
            """(?i)\bEp(?:isode)?\s*[:.]?\s*(\d+)\b"""
        )
        private val TAG_REGEX = Regex("""<[^>]+>""")

        internal fun extractCatalogItems(body: String): List<MovieCatalogItem> {
            val normalizedBody = normalizeEmbeddedText(body)

            val strict = CATALOG_ANCHOR_REGEX.findAll(normalizedBody)
                .mapNotNull { match ->
                    catalogItemFromBlock(
                        slug = match.groupValues.getOrNull(2)?.lowercase().orEmpty(),
                        block = match.groupValues.getOrNull(3).orEmpty()
                    )
                }
                .toList()

            val relaxed = DETAIL_HREF_REGEX.findAll(normalizedBody)
                .mapNotNull { match ->
                    val slug = match.groupValues.getOrNull(2)?.lowercase().orEmpty()
                    if (!SLUG_REGEX.matches(slug)) return@mapNotNull null

                    // Narto cards do not always wrap the title inside the same <a>.
                    // Search a bounded neighborhood around the href so sibling
                    // headings/images/episode labels are still associated.
                    val start = (match.range.first - 1400).coerceAtLeast(0)
                    val end = (match.range.last + 2200).coerceAtMost(normalizedBody.lastIndex)
                    val block = normalizedBody.substring(start, end + 1)
                    catalogItemFromBlock(slug, block)
                }
                .toList()

            return (strict + relaxed)
                .groupBy { it.slug }
                .mapNotNull { (_, sameSlug) ->
                    sameSlug.maxByOrNull {
                        (if (!it.thumbnailUrl.isNullOrBlank()) 2 else 0) +
                            (if (!it.episodeBadge.isNullOrBlank()) 1 else 0) +
                            it.title.length.coerceAtMost(80)
                    }
                }
        }

        private fun catalogItemFromBlock(
            slug: String,
            block: String
        ): MovieCatalogItem? {
            if (!SLUG_REGEX.matches(slug)) return null

            val visible = stripTags(block)
            val heading = HEADING_REGEX.findAll(block)
                .mapNotNull { it.groupValues.getOrNull(1)?.let(::stripTags) }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .minByOrNull { candidate ->
                    // Prefer the heading that semantically resembles the slug.
                    kotlin.math.abs(
                        normalizeSearchText(candidate).length -
                            normalizeSearchText(slug.replace('-', ' ')).length
                    )
                }
                ?: return null

            val epCount = EP_COUNT_REGEX.find(visible)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()

            val image = extractFirstImageUrl(block)

            return MovieCatalogItem(
                providerId = "narto",
                providerName = "Narto Drama",
                slug = slug,
                title = heading,
                originalTitle = null,
                thumbnailUrl = image,
                year = null,
                language = "Tiếng Việt",
                durationText = null,
                durationSeconds = null,
                episodeBadge = epCount?.let { it.toString() + " tập" },
                isSeries = epCount?.let { it > 1 }
            )
        }

        private val IMAGE_ATTR_REGEX = Regex(
            """(?:src|data-src|data-lazy-src|poster)\s*=\s*["']([^"']+)["']""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val SRCSET_ATTR_REGEX = Regex(
            """(?:srcset|data-srcset)\s*=\s*["']([^"']+)["']""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val CSS_URL_REGEX = Regex(
            """url\(\s*['"]?([^)'"]+)['"]?\s*\)""",
            RegexOption.IGNORE_CASE
        )

        internal fun extractFirstImageUrl(block: String): String? {
            val candidates = buildList {
                IMAGE_ATTR_REGEX.findAll(block).forEach { match ->
                    match.groupValues.getOrNull(1)?.let(::add)
                }
                SRCSET_ATTR_REGEX.findAll(block).forEach { match ->
                    match.groupValues.getOrNull(1)
                        ?.split(',')
                        ?.firstOrNull()
                        ?.trim()
                        ?.substringBefore(' ')
                        ?.let(::add)
                }
                CSS_URL_REGEX.findAll(block).forEach { match ->
                    match.groupValues.getOrNull(1)?.let(::add)
                }
            }

            return candidates
                .asSequence()
                .mapNotNull(::normalizeImageUrl)
                .firstOrNull()
        }

        internal fun normalizeImageUrl(value: String): String? {
            var raw = htmlDecode(value).trim()
            if (raw.isBlank() || raw.startsWith("data:", ignoreCase = true)) return null

            raw = raw
                .replace("\\u002F", "/", ignoreCase = true)
                .replace("\\/", "/")

            if (raw.startsWith("//")) raw = "https:" + raw
            if (raw.startsWith("/")) raw = "https://edge.narto-drama.com" + raw

            if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
                return null
            }

            return raw
        }

        internal fun normalizeSearchText(value: String): String {
            val ascii = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replace(Regex("""\p{M}+"""), "")
                .replace('đ', 'd')
                .replace('Đ', 'D')
                .lowercase()
            return ascii.replace(Regex("""[^a-z0-9]+"""), " ")
                .trim()
                .replace(Regex("""\s+"""), " ")
        }

        private fun stripTags(value: String): String =
            htmlDecode(TAG_REGEX.replace(value, " "))
                .replace(Regex("""\s+"""), " ")
                .trim()

        internal fun looksLikeChallenge(
            body: String,
            finalUrl: String,
            statusCode: Int
        ): Boolean {
            val text = body.lowercase()
            val url = finalUrl.lowercase()

            if (
                statusCode == 403 ||
                statusCode == 429 ||
                "challenges.cloudflare.com" in url
            ) {
                return true
            }

            val hasCatalogContent =
                "/detail/watch/" in text &&
                    (text.contains("<h2") || text.contains("<h3"))

            if (hasCatalogContent && body.length > 20_000) {
                return false
            }

            return (
                "just a moment" in text ||
                    "cf-chl-" in text ||
                    "challenge-platform" in text ||
                    "turnstile" in text
                ) && body.length < 50_000
        }

        internal fun slugify(value: String): String {
            val ascii = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replace(Regex("""\p{M}+"""), "")
                .replace('đ', 'd')
                .replace('Đ', 'D')
                .lowercase()
            return ascii.replace(Regex("""[^a-z0-9]+"""), "-")
                .trim('-')
        }

        internal fun parseWatchUrl(value: String): Pair<String, Int?>? {
            val match = WATCH_URL_REGEX.find(value.trim()) ?: return null
            return match.groupValues[1].lowercase() to
                match.groupValues.getOrNull(2)
                    ?.takeIf { it.isNotBlank() }
                    ?.toIntOrNull()
        }

        internal fun extractSignedHlsUrl(body: String): String? {
            val normalized = normalizeEmbeddedText(body)
            ABSOLUTE_HLS_REGEX.find(normalized)?.value?.let { return it }
            val relative = RELATIVE_HLS_REGEX.find(normalized)
                ?.groupValues?.getOrNull(1) ?: return null
            return "https://stream.narto-drama.com" + relative
        }

        internal fun extractSubtitleUrls(body: String): List<String> =
            SUBTITLE_REGEX.findAll(normalizeEmbeddedText(body))
                .map { it.value }
                .distinct()
                .toList()

        internal fun extractEpisodeCount(body: String, slug: String): Int? {
            val normalized = normalizeEmbeddedText(body)
            val explicit = listOf(
                Regex("""(?i)episode\s+\d+\s+(?:dari|of)\s+(\d+)"""),
                Regex("""(?i)(?:total\s+episodes?|episodes?)\s*[:=]\s*(\d+)"""),
                Regex("""(?i)episodes?\s*\(\s*(\d+)\s*\)"""),
                Regex("""(?i)(\d+)\s+episodes?"""),
                Regex("""(?i)playable\s*:\s*(\d+)"""),
                Regex("""(?i)ep\s*[:.]?\s*(\d+)""")
            ).asSequence()
                .mapNotNull { it.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                .filter { it > 0 }
                .maxOrNull()

            val linkedMax = Regex(
                """(?i)/detail/watch/""" + Regex.escape(slug) + """/(\d+)"""
            ).findAll(normalized)
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .filter { it > 0 }
                .maxOrNull()

            return listOfNotNull(explicit, linkedMax).maxOrNull()
        }

        internal fun extractTitle(body: String): String? {
            extractMeta(body, "og:title")?.let { return cleanTitle(it) }
            return TITLE_REGEX.find(body)?.groupValues?.getOrNull(1)
                ?.let(::htmlDecode)?.let(::cleanTitle)?.takeIf { it.isNotBlank() }
        }

        internal fun extractMeta(body: String, key: String): String? {
            META_REGEX.findAll(body).forEach { match ->
                if (htmlDecode(match.groupValues[1]).equals(key, ignoreCase = true)) {
                    return htmlDecode(match.groupValues[2]).trim().takeIf { it.isNotBlank() }
                }
            }
            META_REVERSED_REGEX.findAll(body).forEach { match ->
                if (htmlDecode(match.groupValues[2]).equals(key, ignoreCase = true)) {
                    return htmlDecode(match.groupValues[1]).trim().takeIf { it.isNotBlank() }
                }
            }
            return null
        }

        private fun normalizeEmbeddedText(value: String) =
            value.replace("\\\\/", "/")
                .replace("\\/", "/")
                .replace("\\u002F", "/", ignoreCase = true)
                .replace("\\u003A", ":", ignoreCase = true)
                .replace("&amp;", "&")

        private fun cleanTitle(value: String) =
            value.replace(Regex("""\s+"""), " ")
                .replace(
                    Regex(
                        """\s*(?:[-|·]\s*)?Episode\s+\d+\s*(?:[-|·]\s*)?Free Streaming\s*$""",
                        RegexOption.IGNORE_CASE
                    ),
                    ""
                )
                .replace(
                    Regex(
                        """\s*(?:[-|·]\s*)?Free Streaming\s*$""",
                        RegexOption.IGNORE_CASE
                    ),
                    ""
                )
                .replace(
                    Regex(
                        """\s*(?:[-|·]\s*)?Narto Drama\s*$""",
                        RegexOption.IGNORE_CASE
                    ),
                    ""
                )
                .trim()

        private fun htmlDecode(value: String) =
            value.replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&amp;", "&")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
    }
}
