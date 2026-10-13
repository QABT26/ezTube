package com.qabt.eztube.movie

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.Normalizer
import java.util.concurrent.TimeUnit

class OPhimMovieProvider(
    private val client: OkHttpClient? = null
) : MovieProvider {
    override val id: String = "ophim"
    override val displayName: String = "OPhim"

    private val http = client ?: OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun search(query: String): List<MovieCatalogItem> {
        val normalized = query.trim().replace(Regex("""\s+"""), " ")
        if (normalized.isBlank()) return emptyList()

        val url = "$BASE_URL/v1/api/tim-kiem".toHttpUrl()
            .newBuilder()
            .addQueryParameter("keyword", normalized)
            .addQueryParameter("limit", "24")
            .build()

        val root = getJson(url.toString())
        val data = root.getObject("data") ?: return emptyList()
        val items = data.getArray("items") ?: return emptyList()
        val imageBase = data.getString("APP_DOMAIN_CDN_IMAGE")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        return buildList {
            for (index in 0 until items.size) {
                val item = items.getObject(index) ?: continue
                val slug = item.getString("slug").orEmpty().trim()
                val title = item.getString("name").orEmpty().trim()
                if (slug.isBlank() || title.isBlank()) continue

                val time = item.getString("time")
                add(
                    MovieCatalogItem(
                        providerId = id,
                        providerName = displayName,
                        slug = slug,
                        title = title,
                        originalTitle = item.getString("origin_name"),
                        thumbnailUrl = normalizeImage(
                            item.getString("thumb_url") ?: item.getString("poster_url"),
                            imageBase
                        ),
                        year = item.getInt("year").takeIf { it > 0 },
                        language = item.getString("lang"),
                        durationText = time,
                        durationSeconds = parseDurationSeconds(time),
                        episodeBadge = parseEpisodeBadge(item),
                        isSeries = parseIsSeries(item)
                    )
                )
            }
        }
            .sortedByDescending { localScore(normalized, it) }
            .take(24)
    }

    override fun detail(slug: String): MovieDetail {
        val root = getMovieDetailJson(slug)

        val data = root.getObject("data")
        val movie = root.getObject("movie")
            ?: data?.getObject("item")
            ?: data?.getObject("movie")
            ?: throw IOException("OPhim detail response has no movie")

        val episodes = root.getArray("episodes")
            ?: data?.getArray("episodes")
            ?: throw IOException("OPhim detail response has no episodes")

        val imageBase = data?.getString("APP_DOMAIN_CDN_IMAGE")
        val time = movie.getString("time")
        val catalog = MovieCatalogItem(
            providerId = id,
            providerName = displayName,
            slug = movie.getString("slug").orEmpty().ifBlank { slug },
            title = movie.getString("name").orEmpty().ifBlank { slug.replace('-', ' ') },
            originalTitle = movie.getString("origin_name"),
            thumbnailUrl = normalizeImage(
                movie.getString("thumb_url") ?: movie.getString("poster_url"),
                imageBase
            ),
            year = movie.getInt("year").takeIf { it > 0 },
            language = movie.getString("lang"),
            durationText = time,
            durationSeconds = parseDurationSeconds(time),
            episodeBadge = parseEpisodeBadge(movie),
            isSeries = parseIsSeries(movie)
        )

        val refs = mutableListOf<MovieEpisodeRef>()
        for (serverIndex in 0 until episodes.size) {
            val server = episodes.getObject(serverIndex) ?: continue
            val serverName = server.getString("server_name").orEmpty().ifBlank { "OPhim" }
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
                    episodeSlug = episode.getString("slug").orEmpty()
                        .ifBlank { episode.getString("name").orEmpty() }
                        .ifBlank { "episode-${episodeIndex + 1}" },
                    episodeName = episode.getString("name").orEmpty()
                        .ifBlank { "Tập ${episodeIndex + 1}" },
                    thumbnailUrl = catalog.thumbnailUrl,
                    durationSeconds = catalog.durationSeconds,
                    language = catalog.language
                )
            }
        }

        if (refs.isEmpty()) {
            throw IOException("OPhim returned no playable HLS episodes")
        }

        return MovieDetail(catalog, refs)
    }

    override fun resolve(mediaId: String): MoviePlayback {
        val parts = mediaId.split(':')
        if (parts.size != 5 || parts[0] != "movie" || parts[1] != id) {
            throw IOException("Unsupported OPhim media id")
        }

        val slug = parts[2]
        val serverName = decodeStablePart(parts[3])
        val episodeKey = decodeStablePart(parts[4])
        val root = getMovieDetailJson(slug)

        val data = root.getObject("data")
        val episodes = root.getArray("episodes")
            ?: data?.getArray("episodes")
            ?: throw IOException("OPhim detail has no episode servers")

        var chosen: JsonObject? = null
        for (serverIndex in 0 until episodes.size) {
            val server = episodes.getObject(serverIndex) ?: continue
            if (!server.getString("server_name").orEmpty().equals(serverName, ignoreCase = true)) {
                continue
            }

            val serverData = server.getArray("server_data") ?: continue
            for (episodeIndex in 0 until serverData.size) {
                val episode = serverData.getObject(episodeIndex) ?: continue
                val key = episode.getString("slug").orEmpty()
                    .ifBlank { episode.getString("name").orEmpty() }
                if (key.equals(episodeKey, ignoreCase = true)) {
                    chosen = episode
                    break
                }
            }
            if (chosen != null) break
        }

        val episode = chosen ?: throw IOException("OPhim episode no longer exists")
        val hls = episode.getString("link_m3u8").orEmpty().trim()
        if (hls.isBlank()) throw IOException("OPhim episode has no HLS stream")

        return MoviePlayback(
            mediaId = mediaId,
            hlsUrl = hls,
            subtitles = parseSubtitles(episode)
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
                val url = (
                    sub.getString("url")
                        ?: sub.getString("src")
                        ?: sub.getString("file")
                    )
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

    private fun normalizeImage(value: String?, base: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw

        val cdn = base?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
            ?: IMAGE_CDN

        return when {
            raw.startsWith("/") -> cdn + raw
            raw.startsWith("uploads/") -> "$cdn/$raw"
            else -> "$cdn/uploads/movies/$raw"
        }
    }

    private fun parseIsSeries(item: JsonObject): Boolean? =
        when (item.getString("type")?.trim()?.lowercase()) {
            "series", "tvshows", "tvshow", "episode", "hoathinh" -> true
            "single", "movie" -> false
            else -> null
        }

    private fun parseEpisodeBadge(item: JsonObject): String? {
        val raw = (
            item.getString("episode_current")
                ?: item.getString("episode_total")
            )
            ?.trim()
            .orEmpty()
        if (raw.isBlank()) return null
        if (raw.equals("Full", ignoreCase = true)) return "Full"

        Regex("""(?i)(?:tập|tap)\s*(\d+)""")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.let { return "$it tập" }

        Regex("""(\d+)\s*/\s*(\d+)""")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.let { return "$it tập" }

        return raw.takeIf { it.length <= 24 }
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

    private fun localScore(query: String, item: MovieCatalogItem): Int {
        val q = normalizeText(query)
        val title = normalizeText(item.title)
        val original = normalizeText(item.originalTitle.orEmpty())
        if (q.isBlank()) return 0
        if (title == q || original == q) return 1000
        if (title.startsWith(q) || q.startsWith(title)) return 750
        if ("$title $original".contains(q)) return 650
        return q.split(' ')
            .filter { it.length >= 2 }
            .count { token ->
                title.split(' ').any { word ->
                    word == token ||
                        word.startsWith(token) ||
                        token.startsWith(word)
                }
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
        }.getOrElse { throw IOException("Invalid OPhim stable id") }

    private fun getMovieDetailJson(slug: String): JsonObject {
        val encoded = java.net.URLEncoder.encode(slug, "UTF-8")
        val candidates = listOf(
            "$BASE_URL/v1/api/phim/$encoded",
            "$BASE_URL/phim/$encoded"
        )

        var lastError: Throwable? = null
        for (url in candidates) {
            val result = runCatching { getJson(url) }
            if (result.isSuccess) return result.getOrThrow()

            val error = result.exceptionOrNull()
            lastError = error

            // Only fall through to the legacy endpoint for a missing route/item.
            // Other network errors are still allowed to try the alternate endpoint
            // because OPhim has historically served both API shapes.
        }

        throw IOException(
            lastError?.message ?: "OPhim detail unavailable for $slug",
            lastError
        )
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
                throw IOException("OPhim HTTP ${response.code}")
            }
            if (body.isBlank()) throw IOException("OPhim returned empty body")
            return runCatching { JsonParser.`object`().from(body) }
                .getOrElse { throw IOException("OPhim returned invalid JSON", it) }
        }
    }

    private companion object {
        const val BASE_URL = "https://ophim1.com"
        const val IMAGE_CDN = "https://img.ophim.live"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
    }
}
