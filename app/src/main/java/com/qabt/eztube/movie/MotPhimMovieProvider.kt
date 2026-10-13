package com.qabt.eztube.movie

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import java.text.Normalizer
import java.util.concurrent.TimeUnit

class MotPhimMovieProvider(
    private val client: OkHttpClient? = null
) : MovieProvider {
    override val id = "motphim"
    override val displayName = "MotPhim"

    private val cookieStore = mutableListOf<Cookie>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(cookieStore) {
                cookies.forEach { incoming ->
                    cookieStore.removeAll {
                        it.name == incoming.name &&
                            it.domain == incoming.domain &&
                            it.path == incoming.path
                    }
                    cookieStore += incoming
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            synchronized(cookieStore) {
                cookieStore.filter { it.matches(url) }
            }
    }

    @Volatile
    private var sessionPrimed = false

    private val http = client ?: OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun search(query: String): List<MovieCatalogItem> {
        val normalized = query.trim().replace(Regex("""\s+"""), " ")
        if (normalized.isBlank()) return emptyList()
        return searchRsc(normalized)
    }

    override fun detail(slug: String): MovieDetail {
        throw IOException(
            "MotPhim detail endpoint not wired yet for $slug"
        )
    }

    override fun resolve(mediaId: String): MoviePlayback {
        val parts = mediaId.split(':')
        if (parts.size != 5 || parts[0] != "movie" || parts[1] != id) {
            throw IOException("Unsupported MotPhim media id")
        }

        val slug = parts[2]
        val serverName = decodeStablePart(parts[3])
        val episodeKey = decodeStablePart(parts[4])
        val movie = findMovieBySlug(slug)
            ?: throw IOException("MotPhim movie no longer exists")
        val episodes = movie.getArray("episodes")
            ?: throw IOException("MotPhim movie has no episodes")

        var chosen: JsonObject? = null
        for (index in 0 until episodes.size) {
            val episode = episodes.getObject(index) ?: continue
            val server = episode.getString("server").orEmpty().ifBlank { "Server" }
            val key = episode.getString("slug").orEmpty()
                .ifBlank { episode.getString("name").orEmpty() }
            if (server.equals(serverName, ignoreCase = true) && key == episodeKey) {
                chosen = episode
                break
            }
        }

        val episode = chosen ?: throw IOException("MotPhim episode no longer exists")
        val hls = resolveToHls(episode.getString("link").orEmpty())
            ?: throw IOException("MotPhim source could not be resolved to HLS")

        return MoviePlayback(
            mediaId = mediaId,
            hlsUrl = hls,
            subtitles = parseSubtitles(episode)
        )
    }

    private fun searchRsc(query: String): List<MovieCatalogItem> {
        ensureSession()

        val url = BASE_URL.toHttpUrl()
            .newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", query)
            .addQueryParameter("_rsc", "ez" + System.nanoTime().toString(36))
            .build()

        val referer = BASE_URL.toHttpUrl()
            .newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", query)
            .build()
            .toString()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Language", "vi,en-US;q=0.9,en;q=0.8")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .header("Referer", referer)
            .header("RSC", "1")
            .header("Next-Url", "/search")
            .header("Next-Router-State-Tree", SEARCH_ROUTER_STATE)
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Dest", "empty")
            .get()
            .build()

        val body = http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("MotPhim RSC HTTP ${response.code}")
            }
            text
        }

        if (body.isBlank()) throw IOException("MotPhim RSC returned empty body")
        return parseSearchRsc(body)
    }

    private fun parseSearchRsc(body: String): List<MovieCatalogItem> {
        val rscItems = SEARCH_CARD_REGEX.findAll(body)
            .mapNotNull { match ->
                val title = decodeRscText(match.groupValues[1]).trim()
                val slug = decodeRscText(match.groupValues[2]).trim()
                if (title.isBlank() || slug.isBlank()) return@mapNotNull null

                val start = match.range.first.coerceAtLeast(0)
                val end = (match.range.last + 1400).coerceAtMost(body.length)
                val window = body.substring(start, end)
                val image = SEARCH_IMAGE_REGEX.find(window)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let(::decodeRscText)

                MovieCatalogItem(
                    providerId = id,
                    providerName = displayName,
                    slug = slug,
                    title = title,
                    originalTitle = null,
                    thumbnailUrl = normalizeImage(image),
                    year = null,
                    language = if (window.contains("Vietsub", ignoreCase = true)) "Vietsub" else null,
                    durationText = null,
                    durationSeconds = null,
                    episodeBadge = null,
                    isSeries = null
                )
            }
            .distinctBy { it.slug }
            .toList()

        if (rscItems.isNotEmpty()) return rscItems.take(24)

        val htmlItems = parseSearchHtml(body)
        if (htmlItems.isNotEmpty()) return htmlItems.take(24)

        throw IOException(
            "MotPhim search format not recognized (" +
                body.take(80).replace("\n", " ") +
                ")"
        )
    }

    private fun parseSearchHtml(body: String): List<MovieCatalogItem> {
        data class HtmlCardMatch(
            val match: MatchResult,
            val titleFirst: Boolean
        )

        val matches = buildList {
            HTML_CARD_TITLE_FIRST.findAll(body).forEach {
                add(HtmlCardMatch(it, titleFirst = true))
            }
            HTML_CARD_HREF_FIRST.findAll(body).forEach {
                add(HtmlCardMatch(it, titleFirst = false))
            }
        }

        return matches.mapNotNull { card ->
            val match = card.match
            val title: String
            val slug: String
            if (card.titleFirst) {
                title = htmlDecode(match.groupValues[1]).trim()
                slug = match.groupValues[2].trim()
            } else {
                slug = match.groupValues[1].trim()
                title = htmlDecode(match.groupValues[2]).trim()
            }
            if (title.isBlank() || slug.isBlank()) return@mapNotNull null

            val start = match.range.first.coerceAtLeast(0)
            val end = (match.range.last + 1600).coerceAtMost(body.length)
            val window = body.substring(start, end)
            val image = HTML_IMAGE_REGEX.find(window)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(::htmlDecode)
                ?.let(::decodeImageProxyUrl)

            MovieCatalogItem(
                providerId = id,
                providerName = displayName,
                slug = slug,
                title = title,
                originalTitle = null,
                thumbnailUrl = normalizeImage(image),
                year = null,
                language = if (window.contains("Vietsub", ignoreCase = true)) "Vietsub" else null,
                durationText = null,
                durationSeconds = null,
                episodeBadge = null,
                isSeries = null
            )
        }
            .distinctBy { it.slug }
    }

    private fun decodeImageProxyUrl(value: String): String {
        val raw = value.trim()
        return runCatching {
            val parsed = raw.toHttpUrl()
            parsed.queryParameter("url")?.let { URLDecoder.decode(it, "UTF-8") } ?: raw
        }.getOrDefault(raw)
    }

    private fun htmlDecode(value: String): String =
        value
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

    private fun decodeRscText(value: String): String =
        value
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\n", " ")
            .replace("\\r", " ")
            .replace("\\t", " ")
            .let { text ->
                UNICODE_ESCAPE_REGEX.replace(text) { match ->
                    match.groupValues[1]
                        .toIntOrNull(16)
                        ?.toChar()
                        ?.toString()
                        ?: match.value
                }
            }

    private fun findMovieBySlug(slug: String): JsonObject? {
        val query = slug
            .replace(Regex("""-\d{8,}$"""), "")
            .replace('-', ' ')
            .trim()

        val candidates = buildList {
            addAll(searchRaw(query))
            if (query.contains(' ')) {
                addAll(searchRaw(query.substringBeforeLast(' ')))
            }
        }

        return candidates.firstOrNull { it.getString("slug").orEmpty() == slug }
            ?: candidates.maxByOrNull {
                titleScore(query, it.getString("name").orEmpty())
            }
    }

    private fun searchRaw(query: String): List<JsonObject> {
        if (query.isBlank()) return emptyList()
        ensureSession()
        val url = BASE_URL.toHttpUrl()
            .newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", query)
            .build()

        val root = getJson(url.toString())
        val movies = root.getObject("data")
            ?.getObject("movies")
            ?.getArray("data")
            ?: return emptyList()

        return buildList {
            for (index in 0 until movies.size) {
                movies.getObject(index)?.let(::add)
            }
        }
    }

    private fun toCatalog(movie: JsonObject): MovieCatalogItem? {
        val slug = movie.getString("slug").orEmpty().trim()
        val title = movie.getString("name").orEmpty().trim()
        if (slug.isBlank() || title.isBlank()) return null

        val duration = movie.getString("episode_time")
        val total = movie.getString("episode_total")?.trim()?.toIntOrNull()
        val current = movie.getString("episode_current").orEmpty().trim()
        val badge = when {
            total != null && total > 1 -> "$total tập"
            current.equals("Full", ignoreCase = true) -> "Full"
            total == 1 -> "Full"
            else -> current.takeIf { it.isNotBlank() }
        }

        return MovieCatalogItem(
            providerId = id,
            providerName = displayName,
            slug = slug,
            title = title,
            originalTitle = movie.getString("origin_name"),
            thumbnailUrl = normalizeImage(
                movie.getString("thumb_url") ?: movie.getString("poster_url")
            ),
            year = movie.getInt("publish_year").takeIf { it > 0 },
            language = movie.getString("language"),
            durationText = duration,
            durationSeconds = parseDurationSeconds(duration),
            episodeBadge = badge,
            isSeries = when (movie.getString("type")?.trim()?.lowercase()) {
                "series" -> true
                "single" -> false
                else -> null
            }
        )
    }

    private fun resolveToHls(link: String): String? {
        val raw = link.trim()
        if (raw.isBlank()) return null
        if (raw.contains(".m3u8", ignoreCase = true) &&
            !raw.contains("player/?", ignoreCase = true)
        ) return raw

        runCatching {
            val nested = raw.toHttpUrl().queryParameter("url")
            if (!nested.isNullOrBlank()) {
                val decoded = URLDecoder.decode(nested, "UTF-8")
                if (decoded.contains(".m3u8", ignoreCase = true)) return decoded
            }
        }

        val html = runCatching { getText(raw) }.getOrNull() ?: return null
        val patterns = listOf(
            Regex("""https?://[^"'\\\s]+\.m3u8[^"'\\\s]*""", RegexOption.IGNORE_CASE),
            Regex("""(?i)(?:file|source|src)\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""")
        )
        patterns.forEach { regex ->
            val match = regex.find(html) ?: return@forEach
            val value = match.groupValues.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?: match.value
            return value.replace("\\/", "/")
        }
        return null
    }

    private fun parseSubtitles(item: JsonObject): List<MovieSubtitle> {
        val raw = item.get("subtitles") ?: return emptyList()
        val array = when (raw) {
            is JsonArray -> raw
            is String -> runCatching { JsonParser.array().from(raw) }.getOrNull()
            else -> null
        } ?: return emptyList()

        return buildList {
            for (index in 0 until array.size) {
                val sub = array.getObject(index) ?: continue
                val url = sub.getString("url").orEmpty().trim()
                if (url.isBlank()) continue
                add(
                    MovieSubtitle(
                        language = sub.getString("lang"),
                        label = sub.getString("label"),
                        url = url
                    )
                )
            }
        }
    }

    private fun getJson(url: String): JsonObject {
        val first = getText(url)
        parseJsonOrNull(first)?.let { return it }

        val retryUrl = runCatching {
            url.toHttpUrl()
                .newBuilder()
                .addQueryParameter("_ez", System.currentTimeMillis().toString())
                .build()
                .toString()
        }.getOrDefault(url)

        val second = getText(retryUrl)
        return parseJsonOrNull(second)
            ?: throw IOException(
                "MotPhim returned non-JSON response (" +
                    second.take(80).replace("\n", " ") +
                    ")"
            )
    }

    private fun parseJsonOrNull(body: String): JsonObject? {
        if (body.isBlank()) return null
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("{")) return null
        return runCatching { JsonParser.`object`().from(body) }.getOrNull()
    }

    private fun ensureSession() {
        if (sessionPrimed) return
        synchronized(this) {
            if (sessionPrimed) return
            runCatching {
                executeText(
                    url = BASE_URL,
                    accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    xhr = false
                )
            }
            sessionPrimed = true
        }
    }

    private fun getText(url: String): String =
        executeText(
            url = url,
            accept = "application/json, text/plain, */*",
            xhr = true
        )

    private fun executeText(
        url: String,
        accept: String,
        xhr: Boolean
    ): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept)
            .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Referer", "$BASE_URL/")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-Mode", if (xhr) "cors" else "navigate")
            .header("Sec-Fetch-Dest", if (xhr) "empty" else "document")
            .get()

        if (xhr) {
            builder
                .header("Origin", BASE_URL)
                .header("X-Requested-With", "XMLHttpRequest")
        }

        http.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(
                    "MotPhim HTTP ${response.code} " +
                        (response.header("server") ?: "")
                )
            }
            return body
        }
    }

    private fun normalizeImage(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        return BASE_URL.trimEnd('/') + "/" + raw.trimStart('/')
    }

    private fun parseDurationSeconds(value: String?): Long? {
        val text = value?.lowercase()?.trim().orEmpty()
        Regex("""(\d+)\s*(?:phút|phut|min|minutes?)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.let { return it * 60L }

        Regex("""(\d+)\s*(?:giờ|gio|hours?|h)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.let { return it * 3600L }

        return null
    }

    private fun titleScore(query: String, title: String): Int {
        val q = normalizeText(query)
        val t = normalizeText(title)
        if (q.isBlank() || t.isBlank()) return 0
        if (q == t) return 1000
        if (t.startsWith(q) || q.startsWith(t)) return 700
        val qt = q.split(' ').filter { it.length >= 2 }
        val tt = t.split(' ').toSet()
        return qt.count { token ->
            tt.any { it == token || it.startsWith(token) || token.startsWith(it) }
        } * 100
    }

    private fun normalizeText(value: String): String {
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

    private fun decodeStablePart(value: String): String =
        runCatching {
            String(
                java.util.Base64.getUrlDecoder().decode(value),
                Charsets.UTF_8
            )
        }.getOrElse { throw IOException("Invalid MotPhim stable id") }

    private companion object {
        const val BASE_URL = "https://motphimc.pw"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/152.0.0.0 Safari/537.36"
        const val SEARCH_ROUTER_STATE =
            "%5B%22%22%2C%7B%22children%22%3A%5B%22search%22%2C%7B%22children%22%3A%5B%22__PAGE__%22%2C%7B%7D%2Cnull%2Cnull%5D%7D%2Cnull%2Cnull%5D%7D%2Cnull%2Cnull%2Ctrue%5D"

        val SEARCH_CARD_REGEX = Regex(
            """title\\?":\\?"([^"]+)"[^{}]{0,260}href\\?":\\?"/phim/([^"\\]+)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val SEARCH_IMAGE_REGEX = Regex(
            """src\\?":\\?"([^"]+(?:thumb|poster)\\.webp)""",
            RegexOption.IGNORE_CASE
        )
        val HTML_CARD_TITLE_FIRST = Regex(
            """<a[^>]*title=["']([^"']+)["'][^>]*href=["']/phim/([^"'?#]+)["'][^>]*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val HTML_CARD_HREF_FIRST = Regex(
            """<a[^>]*href=["']/phim/([^"'?#]+)["'][^>]*title=["']([^"']+)["'][^>]*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val HTML_IMAGE_REGEX = Regex(
            """<img[^>]*src=["']([^"']+)["'][^>]*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val UNICODE_ESCAPE_REGEX = Regex("""\\u([0-9a-fA-F]{4})""")
    }
}
