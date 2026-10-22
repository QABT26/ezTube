package com.qabt.eztube.movie

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

class TophimMovieProvider(
    private val client: OkHttpClient? = null
) : MovieProvider {
    override val id = "tophim"
    override val displayName = "Tophim"

    private val http = client ?: OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun search(query: String): List<MovieCatalogItem> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val url = BASE + "/api/search/suggestions?q=" +
            java.net.URLEncoder.encode(q, "UTF-8")
        val body = getText(url, acceptJson = true)
        val root = com.grack.nanojson.JsonParser.`object`().from(body)
        val movies = root.getArray("movies") ?: return emptyList()

        return buildList {
            for (i in 0 until movies.size) {
                val item = movies.getObject(i) ?: continue
                val slug = item.getString("slug").orEmpty().trim()
                val title = item.getString("title").orEmpty().trim()
                if (slug.isBlank() || title.isBlank()) continue
                add(
                    MovieCatalogItem(
                        providerId = id,
                        providerName = displayName,
                        slug = slug,
                        title = title,
                        originalTitle = item.getString("original_title"),
                        thumbnailUrl = normalizeImage(
                            item.getString("poster_url") ?: item.getString("thumb_url")
                        ),
                        year = item.getInt("year").takeIf { it > 0 },
                        language = null,
                        durationText = null,
                        durationSeconds = null,
                        episodeBadge = null,
                        isSeries = null
                    )
                )
            }
        }
    }

    override fun detail(slug: String): MovieDetail {
        val html = getText(BASE + "/phim/" + java.net.URLEncoder.encode(slug, "UTF-8"))
        val body = unescapeSsr(html)
        val titleTag = TITLE.find(html)?.groupValues?.getOrNull(1).orEmpty()
        val titleMatch = TITLE_META.find(titleTag)

        val title = titleMatch?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: slug.replace('-', ' ')
        val original = titleMatch?.groupValues?.getOrNull(2)?.trim()
            ?.takeIf { it.isNotBlank() }
        val year = titleMatch?.groupValues?.getOrNull(3)?.toIntOrNull()
        val quality = QUALITY.find(titleTag)?.value?.uppercase()

        data class RawEpisode(
            val server: String,
            val name: String,
            val slug: String,
            val url: String
        )

        val raw = mutableListOf<RawEpisode>()
        EPISODE.findAll(body).forEach { match ->
            val server = match.groupValues[1].trim().removePrefix("#")
            val name = match.groupValues[2].trim()
            val epSlug = match.groupValues[3].trim()
            val url = decodeJsonString(match.groupValues[4])
            if (
                url.startsWith("http://") || url.startsWith("https://")
            ) {
                if (!url.contains("/embed", true) && !url.contains("embed.php", true)) {
                    raw += RawEpisode(server.ifBlank { "Vietsub" }, name, epSlug, url)
                }
            }
        }
        if (raw.isEmpty()) throw IOException("Tophim returned no direct streams")

        val refs = raw.mapIndexed { index, ep ->
            MovieEpisodeRef(
                providerId = id,
                providerName = displayName,
                movieSlug = slug,
                movieTitle = title,
                serverIndex = raw.take(index).map { it.server }.distinct().indexOf(ep.server)
                    .coerceAtLeast(0),
                episodeIndex = index,
                serverName = ep.server,
                episodeSlug = ep.slug.ifBlank { ep.name.ifBlank { "episode-" + (index + 1) } },
                episodeName = ep.name.ifBlank { "Tập " + (index + 1) },
                thumbnailUrl = null,
                durationSeconds = null,
                language = null
            )
        }

        val numbered = raw.count {
            it.name.contains("tập", true) || Regex("""\d+""").containsMatchIn(it.name)
        }
        return MovieDetail(
            catalog = MovieCatalogItem(
                providerId = id,
                providerName = displayName,
                slug = slug,
                title = title,
                originalTitle = original,
                thumbnailUrl = null,
                year = year,
                language = null,
                durationText = null,
                durationSeconds = null,
                episodeBadge = if (numbered > 1) numbered.toString() + " tập" else quality,
                isSeries = numbered > 1
            ),
            episodes = refs
        )
    }

    override fun resolve(mediaId: String): MoviePlayback {
        val parts = mediaId.split(':')
        if (parts.size != 5 || parts[0] != "movie" || parts[1] != id) {
            throw IOException("Unsupported Tophim media id")
        }
        val slug = parts[2]
        val serverName = decodeStablePart(parts[3])
        val episodeKey = decodeStablePart(parts[4])

        val html = unescapeSsr(
            getText(BASE + "/phim/" + java.net.URLEncoder.encode(slug, "UTF-8"))
        )

        var url: String? = null
        EPISODE.findAll(html).forEach { match ->
            if (url != null) return@forEach
            val server = match.groupValues[1].trim().removePrefix("#").ifBlank { "Vietsub" }
            val name = match.groupValues[2].trim()
            val epSlug = match.groupValues[3].trim()
            val key = epSlug.ifBlank { name }
            val candidate = decodeJsonString(match.groupValues[4])
            if (
                server.equals(serverName, true) &&
                key == episodeKey &&
                (candidate.startsWith("http://") || candidate.startsWith("https://")) &&
                !candidate.contains("/embed", true) &&
                !candidate.contains("embed.php", true)
            ) {
                url = candidate
            }
        }

        val hls = url ?: throw IOException("Tophim episode stream no longer exists")
        return MoviePlayback(
            mediaId = mediaId,
            hlsUrl = hls,
            requestHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to BASE + "/",
                "Origin" to BASE
            ),
            diagnostic = "Provider: Tophim\nPlayback: direct HLS"
        )
    }

    private fun getText(url: String, acceptJson: Boolean = false): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", BASE + "/")
            .header("Accept", if (acceptJson) "application/json" else "text/html,application/xhtml+xml,*/*")
            .get()
            .build()
        return http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Tophim HTTP " + response.code)
            if (body.isBlank()) throw IOException("Tophim returned empty body")
            body
        }
    }

    private fun unescapeSsr(value: String): String {
        var out = value
        repeat(3) {
            out = out.replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return out
    }

    private fun decodeJsonString(value: String): String =
        value.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")

    private fun normalizeImage(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("//") -> "https:" + raw
            raw.startsWith("/") -> BASE + raw
            else -> raw
        }
    }

    private fun decodeStablePart(value: String): String =
        runCatching { String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8) }
            .getOrElse { throw IOException("Invalid Tophim stable id") }

    companion object {
        private const val BASE = "https://tophim.top"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

        private val TITLE = Regex(
            """<title>([^<]+)</title>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val TITLE_META = Regex(
            """Xem Phim\s+(.+?)\s+\(([^()]*)\)\s+\((\d{4})\)""",
            RegexOption.IGNORE_CASE
        )
        private val QUALITY = Regex("""\b(4K|2160p|1080p|HD)\b""", RegexOption.IGNORE_CASE)
        private val EPISODE = Regex(
            """"([^"]+)","name":"([^"]+)","slug":"([^"]+)","video_url":"([^"]+)""""
        )
    }
}
