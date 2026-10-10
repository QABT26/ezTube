package com.qabt.eztube.movie

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class PhimApiMovieProvider(
    private val client: OkHttpClient? = null
) : MovieProvider {
    override val id: String = "phimapi"
    override val displayName: String = "KKPhim"

    private val http = client ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun search(query: String): List<MovieCatalogItem> {
        val normalized = query.trim()
        if (normalized.isBlank()) return emptyList()

        val url = "https://phimapi.com/v1/api/tim-kiem".toHttpUrl()
            .newBuilder()
            .addQueryParameter("keyword", normalized)
            .addQueryParameter("limit", "24")
            .build()

        val root = getJson(url.toString())
        val data = root.getObject("data") ?: return emptyList()
        val items = data.getArray("items") ?: return emptyList()
        val imageBase = imageBase(root, data)

        return buildList {
            for (index in 0 until items.size) {
                val item = items.getObject(index) ?: continue
                val slug = item.getString("slug").orEmpty().trim()
                val name = item.getString("name").orEmpty().trim()
                if (slug.isBlank() || name.isBlank()) continue

                val time = item.getString("time")
                add(
                    MovieCatalogItem(
                        providerId = id,
                        providerName = displayName,
                        slug = slug,
                        title = name,
                        originalTitle = item.getString("origin_name"),
                        thumbnailUrl = normalizeImage(
                            item.getString("thumb_url")
                                ?: item.getString("poster_url"),
                            imageBase
                        ),
                        year = item.getInt("year").takeIf { it > 0 },
                        language = item.getString("lang"),
                        durationText = time,
                        durationSeconds = parseDurationSeconds(time)
                    )
                )
            }
        }
    }

    override fun detail(slug: String): MovieDetail {
        val root = getJson(
            "https://phimapi.com/phim/" +
                java.net.URLEncoder.encode(slug, "UTF-8")
        )
        val movie = root.getObject("movie")
            ?: throw IOException("phimapi detail response has no movie")
        val episodes = root.getArray("episodes")
            ?: throw IOException("phimapi detail response has no episodes")

        val time = movie.getString("time")
        val catalog = MovieCatalogItem(
            providerId = id,
            providerName = displayName,
            slug = movie.getString("slug").orEmpty().ifBlank { slug },
            title = movie.getString("name").orEmpty().ifBlank { slug.replace('-', ' ') },
            originalTitle = movie.getString("origin_name"),
            thumbnailUrl = normalizeImage(
                movie.getString("thumb_url") ?: movie.getString("poster_url"),
                null
            ),
            year = movie.getInt("year").takeIf { it > 0 },
            language = movie.getString("lang"),
            durationText = time,
            durationSeconds = parseDurationSeconds(time)
        )

        val refs = mutableListOf<MovieEpisodeRef>()
        for (serverIndex in 0 until episodes.size) {
            val server = episodes.getObject(serverIndex) ?: continue
            val serverName = server.getString("server_name").orEmpty().ifBlank { "Server" }
            val serverData = server.getArray("server_data") ?: continue
            for (episodeIndex in 0 until serverData.size) {
                val episode = serverData.getObject(episodeIndex) ?: continue
                val hls = episode.getString("link_m3u8").orEmpty().trim()
                if (hls.isBlank()) continue
                refs += MovieEpisodeRef(
                    providerId = id,
                    providerName = displayName,
                    movieSlug = catalog.slug,
                    movieTitle = catalog.title,
                    serverIndex = serverIndex,
                    episodeIndex = episodeIndex,
                    serverName = serverName,
                    episodeName = episode.getString("name").orEmpty()
                        .ifBlank { "Tập ${episodeIndex + 1}" },
                    thumbnailUrl = catalog.thumbnailUrl,
                    durationSeconds = catalog.durationSeconds,
                    language = catalog.language
                )
            }
        }

        if (refs.isEmpty()) throw IOException("phimapi returned no playable HLS episodes")
        return MovieDetail(catalog = catalog, episodes = refs)
    }

    override fun resolve(mediaId: String): MoviePlayback {
        val parts = mediaId.split(':')
        if (parts.size != 5 || parts[0] != "movie" || parts[1] != id) {
            throw IOException("Unsupported movie media id")
        }
        val slug = parts[2]
        val serverIndex = parts[3].toIntOrNull()
            ?: throw IOException("Invalid movie server index")
        val episodeIndex = parts[4].toIntOrNull()
            ?: throw IOException("Invalid movie episode index")

        val root = getJson(
            "https://phimapi.com/phim/" +
                java.net.URLEncoder.encode(slug, "UTF-8")
        )
        val server = root.getArray("episodes")?.getObject(serverIndex)
            ?: throw IOException("Movie server no longer exists")
        val item = server.getArray("server_data")?.getObject(episodeIndex)
            ?: throw IOException("Movie episode no longer exists")
        val hls = item.getString("link_m3u8").orEmpty().trim()
        if (hls.isBlank()) throw IOException("Movie episode has no HLS stream")

        return MoviePlayback(
            mediaId = mediaId,
            hlsUrl = hls,
            subtitles = parseSubtitles(item)
        )
    }

    private fun parseSubtitles(item: JsonObject): List<MovieSubtitle> {
        val raw = item.get("subtitles") ?: item.get("subtitle") ?: item.get("tracks")
        val array = when (raw) {
            is JsonArray -> raw
            is String -> runCatching { JsonParser.array().from(raw) }.getOrNull()
            else -> null
        } ?: return emptyList()

        return buildList {
            for (index in 0 until array.size) {
                val sub = array.getObject(index) ?: continue
                val url = (sub.getString("url")
                    ?: sub.getString("src")
                    ?: sub.getString("file"))
                    .orEmpty()
                    .trim()
                if (url.isBlank()) continue
                add(
                    MovieSubtitle(
                        language = sub.getString("lang")
                            ?: sub.getString("language")
                            ?: sub.getString("srclang"),
                        label = sub.getString("label")
                            ?: sub.getString("name"),
                        url = url
                    )
                )
            }
        }
    }

    private fun getJson(url: String): JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("phimapi HTTP ${response.code}")
            }
            if (body.isBlank()) throw IOException("phimapi returned empty body")
            return JsonParser.object().from(body)
        }
    }

    private fun imageBase(root: JsonObject, data: JsonObject): String? =
        data.getString("APP_DOMAIN_CDN_IMAGE")
            ?: data.getString("pathImage")
            ?: root.getString("pathImage")

    private fun normalizeImage(value: String?, base: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val prefix = base?.trimEnd('/')
            ?: "https://phimimg.com"
        return prefix + "/" + raw.trimStart('/')
    }

    private fun parseDurationSeconds(value: String?): Long? {
        val text = value?.lowercase()?.trim().orEmpty()
        if (text.isBlank()) return null
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

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
    }
}
