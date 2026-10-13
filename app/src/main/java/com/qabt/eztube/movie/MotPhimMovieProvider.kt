package com.qabt.eztube.movie

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
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

    private val http = client ?: OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun search(query: String): List<MovieCatalogItem> {
        val normalized = query.trim().replace(Regex("""\s+"""), " ")
        if (normalized.isBlank()) return emptyList()
        return searchRaw(normalized).mapNotNull(::toCatalog)
    }

    override fun detail(slug: String): MovieDetail {
        val movie = findMovieBySlug(slug)
            ?: throw IOException("MotPhim movie not found: $slug")
        val catalog = toCatalog(movie)
            ?: throw IOException("MotPhim movie metadata is incomplete")

        val episodes = movie.getArray("episodes")
            ?: throw IOException("MotPhim movie has no episodes")
        val refs = mutableListOf<MovieEpisodeRef>()
        for (index in 0 until episodes.size) {
            val episode = episodes.getObject(index) ?: continue
            val link = episode.getString("link").orEmpty().trim()
            if (link.isBlank()) continue
            val server = episode.getString("server").orEmpty().ifBlank { "Server" }
            val name = episode.getString("name").orEmpty()
                .ifBlank { if (catalog.isSeries == false) "Full" else "Tập ${index + 1}" }

            refs += MovieEpisodeRef(
                providerId = id,
                providerName = displayName,
                movieSlug = catalog.slug,
                movieTitle = catalog.title,
                serverIndex = 0,
                episodeIndex = index,
                serverName = server,
                episodeSlug = episode.getString("slug").orEmpty()
                    .ifBlank { name }
                    .ifBlank { "episode-${index + 1}" },
                episodeName = name,
                thumbnailUrl = catalog.thumbnailUrl,
                durationSeconds = catalog.durationSeconds,
                language = catalog.language
            )
        }

        if (refs.isEmpty()) throw IOException("MotPhim returned no playable episodes")
        return MovieDetail(catalog, refs)
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
        val body = getText(url)
        if (body.isBlank()) throw IOException("MotPhim returned empty body")
        return runCatching { JsonParser.`object`().from(body) }
            .getOrElse { throw IOException("MotPhim returned invalid JSON", it) }
    }

    private fun getText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json,text/plain,*/*")
            .header("Referer", "$BASE_URL/")
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("MotPhim HTTP ${response.code}")
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
    }
}
