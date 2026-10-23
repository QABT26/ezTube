package com.qabt.eztube.movie

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

class NguonCMovieProvider(
    private val client: OkHttpClient? = null
) : MovieProvider {
    override val id = "nguonc"
    override val displayName = "NguonC"

    private val http = client ?: OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun search(query: String): List<MovieCatalogItem> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val root = getJson(
            API_BASE + "/films/search?keyword=" +
                java.net.URLEncoder.encode(q, "UTF-8") + "&page=1"
        )
        val items = root.getArray("items") ?: return emptyList()
        return buildList {
            for (i in 0 until items.size) {
                val item = items.getObject(i) ?: continue
                val slug = item.getString("slug").orEmpty().trim()
                val name = item.getString("name").orEmpty().trim()
                if (slug.isBlank() || name.isBlank()) continue
                add(catalogItem(item, slug, name))
            }
        }
    }

    override fun detail(slug: String): MovieDetail {
        val root = getJson(API_BASE + "/film/" + java.net.URLEncoder.encode(slug, "UTF-8"))
        val movie = root.getObject("movie") ?: root
        val movieSlug = movie.getString("slug").orEmpty().ifBlank { slug }
        val title = movie.getString("name").orEmpty().ifBlank { slug.replace('-', ' ') }
        val catalog = catalogItem(movie, movieSlug, title)
        val episodes = movie.getArray("episodes") ?: root.getArray("episodes")
            ?: throw IOException("NguonC detail has no episodes")

        val refs = mutableListOf<MovieEpisodeRef>()
        for (serverIndex in 0 until episodes.size) {
            val server = episodes.getObject(serverIndex) ?: continue
            val serverName = server.getString("server_name").orEmpty().ifBlank { "Vietsub" }
            val epItems = server.getArray("items") ?: server.getArray("server_data") ?: continue
            for (episodeIndex in 0 until epItems.size) {
                val ep = epItems.getObject(episodeIndex) ?: continue
                if (!hasPlayableSource(ep)) continue
                refs += MovieEpisodeRef(
                    providerId = id,
                    providerName = displayName,
                    movieSlug = movieSlug,
                    movieTitle = title,
                    serverIndex = serverIndex,
                    episodeIndex = episodeIndex,
                    serverName = serverName,
                    episodeSlug = episodeKey(ep, episodeIndex),
                    episodeName = ep.getString("name").orEmpty()
                        .ifBlank { "Tập " + (episodeIndex + 1) },
                    thumbnailUrl = catalog.thumbnailUrl,
                    durationSeconds = catalog.durationSeconds,
                    language = movie.getString("language")
                )
            }
        }
        if (refs.isEmpty()) throw IOException("NguonC returned no playable episodes")
        return MovieDetail(catalog, refs)
    }

    override fun resolve(mediaId: String): MoviePlayback {
        val parts = mediaId.split(':')
        if (parts.size != 5 || parts[0] != "movie" || parts[1] != id) {
            throw IOException("Unsupported NguonC media id")
        }
        val slug = parts[2]
        val serverName = decodeStablePart(parts[3])
        val targetEpisodeKey = decodeStablePart(parts[4])

        val root = getJson(API_BASE + "/film/" + java.net.URLEncoder.encode(slug, "UTF-8"))
        val movie = root.getObject("movie") ?: root
        val episodes = movie.getArray("episodes") ?: root.getArray("episodes")
            ?: throw IOException("NguonC detail has no episodes")

        var item: JsonObject? = null
        var matchedServer = serverName
        loop@ for (serverIndex in 0 until episodes.size) {
            val server = episodes.getObject(serverIndex) ?: continue
            val name = server.getString("server_name").orEmpty().ifBlank { "Vietsub" }
            if (!name.equals(serverName, true)) continue
            val epItems = server.getArray("items") ?: server.getArray("server_data") ?: continue
            for (episodeIndex in 0 until epItems.size) {
                val candidate = epItems.getObject(episodeIndex) ?: continue
                if (episodeKey(candidate, episodeIndex) == targetEpisodeKey) {
                    item = candidate
                    matchedServer = name
                    break@loop
                }
            }
        }
        val ep = item ?: throw IOException("NguonC episode no longer exists")
        val direct = firstString(ep, "m3u8", "link_m3u8")
        val embed = firstString(ep, "embed", "link_embed")
        val extracted = when {
            !direct.isNullOrBlank() -> ExtractedStream(direct, REFERER)
            !embed.isNullOrBlank() -> extractEmbed(embed)
            else -> null
        } ?: throw IOException("NguonC episode has no playable HLS stream")

        return MoviePlayback(
            mediaId = mediaId,
            hlsUrl = extracted.url,
            requestHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to extracted.referer,
                "Origin" to originOf(extracted.referer)
            ),
            diagnostic = "Provider: NguonC\nServer: " + matchedServer +
                "\nPlayback: direct HLS\nSource: " +
                if (!direct.isNullOrBlank()) "API m3u8" else "resolved embed"
        )
    }

    private data class ExtractedStream(val url: String, val referer: String)

    private fun extractEmbed(embedUrl: String): ExtractedStream? {
        val request = Request.Builder()
            .url(embedUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .get()
            .build()
        val html = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string().orEmpty()
        }
        if (html.isBlank()) return null

        val origin = runCatching {
            val parsed = embedUrl.toHttpUrl()
            parsed.scheme + "://" + parsed.host +
                if (parsed.port == 80 || parsed.port == 443) "/" else ":" + parsed.port + "/"
        }.getOrDefault(REFERER)

        DATA_OBF.find(html)?.groupValues?.getOrNull(1)?.let { encoded ->
            runCatching {
                val decoded = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
                JsonParser.`object`().from(decoded).getString("sUb")
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()?.let { path ->
                val url = if (path.startsWith("http")) path
                    else origin.trimEnd('/') + "/" + path.trimStart('/')
                return ExtractedStream(url, origin)
            }
        }

        ABSOLUTE_M3U8.find(html)?.value?.let {
            return ExtractedStream(it.replace("&amp;", "&").replace("\\u0026", "&"), origin)
        }
        return null
    }

    private fun catalogItem(item: JsonObject, slug: String, name: String): MovieCatalogItem {
        val currentEpisode = item.getString("current_episode")
        val totalEpisodes = item.getInt("total_episodes").takeIf { it > 0 }
        val series = (totalEpisodes ?: 0) > 1 ||
            (!currentEpisode.isNullOrBlank() && !currentEpisode.contains("full", true))
        val time = item.getString("time")
        return MovieCatalogItem(
            providerId = id,
            providerName = displayName,
            slug = slug,
            title = name,
            originalTitle = item.getString("original_name"),
            thumbnailUrl = normalizeImage(item.getString("poster_url") ?: item.getString("thumb_url")),
            year = item.getInt("year").takeIf { it > 0 },
            language = item.getString("language"),
            durationText = time,
            durationSeconds = parseDurationSeconds(time),
            episodeBadge = totalEpisodes?.let { it.toString() + " tập" }
                ?: currentEpisode?.takeIf { it.isNotBlank() },
            isSeries = series
        )
    }

    private fun hasPlayableSource(item: JsonObject) =
        !firstString(item, "m3u8", "link_m3u8", "embed", "link_embed").isNullOrBlank()

    private fun episodeKey(item: JsonObject, index: Int) =
        item.getString("slug").orEmpty()
            .ifBlank { item.getString("name").orEmpty() }
            .ifBlank { "episode-" + (index + 1) }

    private fun firstString(obj: JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> obj.getString(key)?.trim()?.takeIf { it.isNotBlank() } }

    private fun getJson(url: String): JsonObject {
        var last: Throwable? = null
        for (base in listOf(API_BASE, API_FALLBACK)) {
            val target = if (url.startsWith(API_BASE)) url.replaceFirst(API_BASE, base) else url
            val result = runCatching {
                val request = Request.Builder()
                    .url(target)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Referer", REFERER)
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw IOException("NguonC HTTP " + response.code)
                    if (body.isBlank()) throw IOException("NguonC returned empty body")
                    JsonParser.`object`().from(body)
                }
            }
            if (result.isSuccess) return result.getOrThrow()
            last = result.exceptionOrNull()
        }
        throw IOException(last?.message ?: "NguonC unavailable", last)
    }

    private fun normalizeImage(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("//") -> "https:" + raw
            raw.startsWith("/") -> "https://phim.nguonc.com" + raw
            else -> "https://phim.nguonc.com/" + raw
        }
    }

    private fun decodeStablePart(value: String): String =
        runCatching { String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8) }
            .getOrElse { throw IOException("Invalid NguonC stable id") }

    private fun originOf(url: String): String =
        runCatching {
            val h = url.toHttpUrl()
            h.scheme + "://" + h.host +
                if (h.port == 443 || h.port == 80) "" else ":" + h.port
        }.getOrDefault("https://phim.nguonc.com")

    private fun parseDurationSeconds(value: String?): Long? =
        Regex("""(\d+)\s*(?:phút|phut|min)""", RegexOption.IGNORE_CASE)
            .find(value.orEmpty())?.groupValues?.getOrNull(1)?.toLongOrNull()?.times(60L)

    companion object {
        private const val API_BASE = "https://phim.nguonc.com/api"
        private const val API_FALLBACK = "https://nguonc.com/api"
        private const val REFERER = "https://phim.nguonc.com/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
        private val DATA_OBF = Regex("""data-obf=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val ABSOLUTE_M3U8 = Regex(
            """https?://[^"'\s)]+\.m3u8[^"'\s)]*""",
            RegexOption.IGNORE_CASE
        )
    }
}
