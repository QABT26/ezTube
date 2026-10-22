package com.qabt.eztube.movie

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

class Phim4KMovieProvider(
    private val client: OkHttpClient? = null
) : MovieProvider {
    override val id = "phim4k"
    override val displayName = "Phim4K"

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
            API_BASE + "/tim-kiem?keyword=" +
                java.net.URLEncoder.encode(q, "UTF-8") + "&page=1"
        )
        val items = root.getArray("items")
            ?: root.getObject("data")?.getArray("items")
            ?: return emptyList()

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
        val root = getJson(
            API_BASE + "/phim/" + java.net.URLEncoder.encode(slug, "UTF-8")
        )
        val data = root.getObject("data")
        val movie = root.getObject("movie") ?: data?.getObject("movie")
            ?: throw IOException("Phim4K detail has no movie")
        val episodes = root.getArray("episodes") ?: data?.getArray("episodes")
            ?: throw IOException("Phim4K detail has no episodes")

        val movieSlug = movie.getString("slug").orEmpty().ifBlank { slug }
        val title = movie.getString("name").orEmpty().ifBlank { slug.replace('-', ' ') }
        val catalog = catalogItem(movie, movieSlug, title)

        val refs = mutableListOf<MovieEpisodeRef>()
        for (serverIndex in 0 until episodes.size) {
            val server = episodes.getObject(serverIndex) ?: continue
            val serverName = server.getString("server_name").orEmpty().ifBlank { "Vietsub" }
            val items = server.getArray("server_data") ?: server.getArray("items") ?: continue
            for (episodeIndex in 0 until items.size) {
                val ep = items.getObject(episodeIndex) ?: continue
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
                    language = movie.getString("lang")
                )
            }
        }
        if (refs.isEmpty()) throw IOException("Phim4K returned no playable episodes")
        return MovieDetail(catalog, refs)
    }

    override fun resolve(mediaId: String): MoviePlayback {
        val parts = mediaId.split(':')
        if (parts.size != 5 || parts[0] != "movie" || parts[1] != id) {
            throw IOException("Unsupported Phim4K media id")
        }
        val slug = parts[2]
        val serverName = decodeStablePart(parts[3])
        val targetEpisodeKey = decodeStablePart(parts[4])

        val root = getJson(
            API_BASE + "/phim/" + java.net.URLEncoder.encode(slug, "UTF-8")
        )
        val data = root.getObject("data")
        val episodes = root.getArray("episodes") ?: data?.getArray("episodes")
            ?: throw IOException("Phim4K detail has no episodes")

        var item: JsonObject? = null
        loop@ for (serverIndex in 0 until episodes.size) {
            val server = episodes.getObject(serverIndex) ?: continue
            val name = server.getString("server_name").orEmpty().ifBlank { "Vietsub" }
            if (!name.equals(serverName, true)) continue
            val items = server.getArray("server_data") ?: server.getArray("items") ?: continue
            for (episodeIndex in 0 until items.size) {
                val candidate = items.getObject(episodeIndex) ?: continue
                if (episodeKey(candidate, episodeIndex) == targetEpisodeKey) {
                    item = candidate
                    break@loop
                }
            }
        }

        val ep = item ?: throw IOException("Phim4K episode no longer exists")
        val direct = firstString(ep, "link_m3u8", "m3u8")
        val embed = firstString(ep, "link_embed", "embed")
        val resolved = when {
            !direct.isNullOrBlank() -> direct
            !embed.isNullOrBlank() -> resolveEmbed(embed)
            else -> null
        } ?: throw IOException("Phim4K episode has no playable HLS stream")

        return MoviePlayback(
            mediaId = mediaId,
            hlsUrl = resolved,
            requestHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to BASE_REFERER,
                "Origin" to "https://vsmov.com"
            ),
            diagnostic = "Provider: Phim4K\nPlayback: direct HLS\nSource: " +
                if (!direct.isNullOrBlank()) "API m3u8" else "resolved embed"
        )
    }

    private fun resolveEmbed(embedUrl: String): String? {
        val request = Request.Builder()
            .url(embedUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", BASE_REFERER)
            .get()
            .build()
        val html = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string().orEmpty()
        }
        if (html.isBlank()) return null

        val base = BASE_URL.find(html)?.groupValues?.getOrNull(1)
        val hash = VIDEO_HASH.find(html)?.groupValues?.getOrNull(1)
        if (!base.isNullOrBlank() && !hash.isNullOrBlank()) {
            return base.trimEnd('/') + "/stream/" + hash + "/master.m3u8"
        }
        return DIRECT_M3U8.find(html)?.value
    }

    private fun catalogItem(item: JsonObject, slug: String, name: String): MovieCatalogItem {
        val time = item.getString("time")
        val type = item.getString("type").orEmpty()
        val isSeries = type.equals("series", true) ||
            type.equals("hoathinh", true) ||
            type.equals("tvshows", true)
        return MovieCatalogItem(
            providerId = id,
            providerName = displayName,
            slug = slug,
            title = name,
            originalTitle = item.getString("origin_name"),
            thumbnailUrl = normalizeImage(item.getString("poster_url") ?: item.getString("thumb_url")),
            year = item.getInt("year").takeIf { it > 0 },
            language = item.getString("lang"),
            durationText = time,
            durationSeconds = parseDurationSeconds(time),
            episodeBadge = item.getString("episode_current")
                ?.takeIf { it.isNotBlank() },
            isSeries = isSeries
        )
    }

    private fun hasPlayableSource(item: JsonObject) =
        !firstString(item, "link_m3u8", "m3u8", "link_embed", "embed").isNullOrBlank()

    private fun episodeKey(item: JsonObject, index: Int) =
        item.getString("slug").orEmpty()
            .ifBlank { item.getString("name").orEmpty() }
            .ifBlank { "episode-" + (index + 1) }

    private fun firstString(obj: JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> obj.getString(key)?.trim()?.takeIf { it.isNotBlank() } }

    private fun getJson(url: String): JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Referer", BASE_REFERER)
            .get()
            .build()
        return http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Phim4K HTTP " + response.code)
            if (body.isBlank()) throw IOException("Phim4K returned empty body")
            JsonParser.`object`().from(body)
        }
    }

    private fun normalizeImage(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("//") -> "https:" + raw
            raw.startsWith("/") -> "https://vsmov.com" + raw
            else -> raw
        }
    }

    private fun decodeStablePart(value: String): String =
        runCatching { String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8) }
            .getOrElse { throw IOException("Invalid Phim4K stable id") }

    private fun parseDurationSeconds(value: String?): Long? =
        Regex("""(\d+)\s*(?:phút|phut|min)""", RegexOption.IGNORE_CASE)
            .find(value.orEmpty())?.groupValues?.getOrNull(1)?.toLongOrNull()?.times(60L)

    companion object {
        private const val API_BASE = "https://vsmov.com/api"
        private const val BASE_REFERER = "https://vsmov.com/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
        private val BASE_URL = Regex("""baseUrl\s*=\s*"([^"]+)"""")
        private val VIDEO_HASH = Regex("""videoHash\s*=\s*"([^"]+)"""")
        private val DIRECT_M3U8 = Regex(
            """https?://[^"'\s]+\.m3u8[^"'\s]*""",
            RegexOption.IGNORE_CASE
        )
    }
}
