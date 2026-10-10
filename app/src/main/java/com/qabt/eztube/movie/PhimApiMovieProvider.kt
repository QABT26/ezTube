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
        val normalized = query.trim().replace(Regex("""\s+"""), " ")
        if (normalized.isBlank()) return emptyList()

        val queryKey = normalizeSearchText(normalized)
        val queryTokens = queryKey.split(' ').filter { it.length >= 2 }.distinct()
        if (queryTokens.isEmpty()) return emptyList()

        val direct = runCatching { searchOnce(normalized) }.getOrDefault(emptyList())
        val rankedDirect = rankCandidates(queryKey, queryTokens, direct)

        // Do not trust phimapi ordering for partial titles. If direct search already
        // has a strong locally-matched result, return the filtered/ranked list.
        if (rankedDirect.isNotEmpty()) return rankedDirect

        // When a title contains a typo or an extra word, searching single words is
        // too noisy. Retry with meaningful title phrases, then rank against the
        // complete user query. Example:
        // "trúng số độc đắc tôi phải đi" -> prefix "trúng số độc đắc".
        val surfaceTokens = normalized.split(' ').filter { it.isNotBlank() }
        val fallbackQueries = buildList {
            val accentless = stripVietnamese(normalized)
            if (!accentless.equals(normalized, ignoreCase = true)) add(accentless)

            if (surfaceTokens.size >= 4) add(surfaceTokens.take(4).joinToString(" "))
            if (surfaceTokens.size >= 3) add(surfaceTokens.take(3).joinToString(" "))
            if (surfaceTokens.size >= 5) add(surfaceTokens.takeLast(4).joinToString(" "))
            if (surfaceTokens.size >= 4) add(surfaceTokens.dropLast(1).joinToString(" "))

            // A few long tokens remain useful as a last resort, but phrase retries
            // are intentionally attempted first.
            queryTokens
                .filter { it.length >= 4 }
                .sortedByDescending { it.length }
                .take(2)
                .forEach(::add)
        }
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals(normalized, ignoreCase = true) }
            .distinctBy { it.lowercase() }

        val candidates = buildList {
            addAll(direct)
            fallbackQueries.forEach { fallback ->
                addAll(runCatching { searchOnce(fallback) }.getOrDefault(emptyList()))
            }
        }.distinctBy { it.providerId + ":" + it.slug }

        return rankCandidates(queryKey, queryTokens, candidates)
    }

    private fun rankCandidates(
        queryKey: String,
        queryTokens: List<String>,
        candidates: List<MovieCatalogItem>
    ): List<MovieCatalogItem> {
        if (candidates.isEmpty()) return emptyList()

        val minimumMatches = when (queryTokens.size) {
            1 -> 1
            2, 3 -> 2
            else -> maxOf(2, (queryTokens.size + 1) / 2)
        }

        return candidates
            .map { item ->
                val itemKey = normalizeSearchText(
                    item.title + " " + item.originalTitle.orEmpty()
                )
                val itemTokens = itemKey.split(' ').filter { it.isNotBlank() }
                val matched = queryTokens.count { token ->
                    itemTokens.any { word ->
                        word == token ||
                            (token.length >= 3 && word.startsWith(token)) ||
                            (word.length >= 3 && token.startsWith(word))
                    }
                }
                val phraseBonus = when {
                    itemKey == queryKey -> 180
                    itemKey.startsWith(queryKey) || queryKey.startsWith(itemKey) -> 120
                    itemKey.contains(queryKey) -> 100
                    else -> 0
                }
                val coverageBonus =
                    ((matched.toFloat() / queryTokens.size.toFloat()) * 60f).toInt()

                item to (matched * 30 + coverageBonus + phraseBonus)
            }
            .filter { (item, _) ->
                val itemKey = normalizeSearchText(
                    item.title + " " + item.originalTitle.orEmpty()
                )
                val itemTokens = itemKey.split(' ').filter { it.isNotBlank() }
                val matched = queryTokens.count { token ->
                    itemTokens.any { word ->
                        word == token ||
                            (token.length >= 3 && word.startsWith(token)) ||
                            (word.length >= 3 && token.startsWith(word))
                    }
                }
                matched >= minimumMatches
            }
            .sortedWith(
                compareByDescending<Pair<MovieCatalogItem, Int>> { it.second }
                    .thenByDescending { it.first.year ?: 0 }
            )
            .map { it.first }
            .take(24)
    }

    private fun searchOnce(query: String): List<MovieCatalogItem> {
        val url = "https://phimapi.com/v1/api/tim-kiem".toHttpUrl()
            .newBuilder()
            .addQueryParameter("keyword", query)
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
                        durationSeconds = parseDurationSeconds(time),
                        episodeBadge = parseEpisodeBadge(item)
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
            durationSeconds = parseDurationSeconds(time),
            episodeBadge = parseEpisodeBadge(movie)
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

        if (refs.isEmpty()) throw IOException("phimapi returned no playable HLS episodes")
        return MovieDetail(catalog = catalog, episodes = refs)
    }

    override fun resolve(mediaId: String): MoviePlayback {
        val parts = mediaId.split(':')
        if (parts.size != 5 || parts[0] != "movie" || parts[1] != id) {
            throw IOException("Unsupported movie media id")
        }
        val slug = parts[2]
        val serverName = decodeStablePart(parts[3])
        val episodeKey = decodeStablePart(parts[4])

        val root = getJson(
            "https://phimapi.com/phim/" +
                java.net.URLEncoder.encode(slug, "UTF-8")
        )
        val episodes = root.getArray("episodes")
            ?: throw IOException("Movie detail has no episode servers")

        var resolvedItem: JsonObject? = null
        for (serverIndex in 0 until episodes.size) {
            val server = episodes.getObject(serverIndex) ?: continue
            if (!server.getString("server_name").orEmpty().equals(serverName, ignoreCase = true)) {
                continue
            }
            val serverData = server.getArray("server_data") ?: continue
            for (episodeIndex in 0 until serverData.size) {
                val candidate = serverData.getObject(episodeIndex) ?: continue
                val candidateKey = candidate.getString("slug").orEmpty()
                    .ifBlank { candidate.getString("name").orEmpty() }
                if (candidateKey == episodeKey) {
                    resolvedItem = candidate
                    break
                }
            }
            if (resolvedItem != null) break
        }

        val item = resolvedItem ?: throw IOException("Movie episode no longer exists")
        val hls = item.getString("link_m3u8").orEmpty().trim()
        if (hls.isBlank()) throw IOException("Movie episode has no HLS stream")

        return MoviePlayback(
            mediaId = mediaId,
            hlsUrl = hls,
            subtitles = parseSubtitles(item)
        )
    }

    private fun decodeStablePart(value: String): String =
        runCatching {
            String(
                java.util.Base64.getUrlDecoder().decode(value),
                Charsets.UTF_8
            )
        }.getOrElse { throw IOException("Invalid stable movie id") }

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
            return JsonParser.`object`().from(body)
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

    private fun parseEpisodeBadge(item: JsonObject): String? {
        val raw = (
            item.getString("episode_current")
                ?: item.getString("episode_total")
                ?: item.getString("current_episode")
                ?: item.getString("total_episodes")
            )
            ?.trim()
            .orEmpty()

        if (raw.isBlank()) return null

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

        Regex("""\d+""")
            .find(raw)
            ?.value
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.let { return "$it tập" }

        return null
    }

    private fun normalizeSearchText(value: String): String =
        stripVietnamese(value)
            .lowercase()
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")

    private fun stripVietnamese(value: String): String {
        val normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        return normalized
            .replace(Regex("""\p{M}+"""), "")
            .replace('đ', 'd')
            .replace('Đ', 'D')
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
