package com.qabt.eztube.movie

import com.grack.nanojson.JsonParser
import com.qabt.eztube.youtube.MediaSummary
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class GenericMovieSourceAdapter(
    private val provider: MovieProviderConfig,
    client: OkHttpClient? = null
) {
    private val http = client ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun canHandle(input: String): Boolean {
        val normalized = input.trim()
        if (normalized.matches(Regex("""movie:\d+""", RegexOption.IGNORE_CASE))) {
            return true
        }

        val inputUrl = normalized.toHttpUrlOrNull() ?: return false
        val inputHost = inputUrl.host.lowercase()

        val knownHost = provider.baseUrls.any { base ->
            base.toHttpUrlOrNull()?.host?.equals(inputHost, ignoreCase = true) == true
        }
        if (knownHost) return true

        return provider.hostHints.any { hint ->
            inputHost.contains(hint.lowercase())
        } && inputUrl.encodedPath.contains("/phim/", ignoreCase = true)
    }

    fun loadAsMedia(input: String): List<MediaSummary> {
        val normalized = input.trim()
        val directId = Regex("""movie:(\d+)""", RegexOption.IGNORE_CASE)
            .matchEntire(normalized)
            ?.groupValues
            ?.getOrNull(1)

        val page = if (directId == null) fetchMoviePage(normalized) else null
        val movieId = directId ?: extractMovieId(page?.html.orEmpty())
            ?: throw IOException("Could not determine movie_id from provider page")
        val title = page?.let { extractTitle(it.html) }
            ?.takeIf { it.isNotBlank() }
            ?: provider.displayName + " " + movieId

        val preferredBaseUrl = page?.url
            ?.toHttpUrlOrNull()
            ?.let { url -> "${url.scheme}://${url.host}" }

        return loadEpisodes(movieId, preferredBaseUrl)
            .preferredHlsEpisodes()
            .map { episode ->
                episode.toMediaSummary(
                    movieTitle = title,
                    providerName = provider.displayName
                )
            }
    }

    fun loadEpisodes(
        movieId: String,
        preferredBaseUrl: String? = null
    ): MovieEpisodeCatalog {
        require(movieId.isNotBlank()) { "movieId must not be blank" }

        var lastError: Throwable? = null
        val bases = buildList {
            preferredBaseUrl?.takeIf { it.isNotBlank() }?.let(::add)
            addAll(provider.baseUrls)
        }.distinct()

        for (baseUrl in bases) {
            for (url in provider.episodesUrls(baseUrl, movieId)) {
                try {
                    val body = get(url, referer = baseUrl + "/")
                    return parseEpisodeCatalog(body)
                } catch (error: Throwable) {
                    lastError = error
                }
            }
        }

        throw IOException(
            "Unable to load movie episodes from ${provider.displayName}",
            lastError
        )
    }

    internal fun parseEpisodeCatalog(json: String): MovieEpisodeCatalog {
        val root = JsonParser.`object`().from(json)
        val status = root.getString("status")
        if (status != null && !status.equals("success", ignoreCase = true)) {
            throw IOException("Movie provider returned status=$status")
        }

        val movieId = root.get("movie_id")?.toString().orEmpty()
        if (movieId.isBlank()) throw IOException("Movie provider response has no movie_id")

        val serverArray = root.getArray("servers")
            ?: throw IOException("Movie provider response has no servers")

        val servers = linkedMapOf<String, MutableList<MovieEpisode>>()
        for (serverIndex in 0 until serverArray.size) {
            val serverObject = serverArray.getObject(serverIndex) ?: continue
            val serverName = serverObject.getString("name").orEmpty().ifBlank { "Server" }
            val itemArray = serverObject.getArray("items") ?: continue
            val target = servers.getOrPut(serverName) { mutableListOf() }

            for (itemIndex in 0 until itemArray.size) {
                val item = itemArray.getObject(itemIndex) ?: continue
                val link = item.getString("link").orEmpty().trim()
                if (link.isBlank()) continue

                val subtitles = parseSubtitles(item.getString("subtitles"))
                target += MovieEpisode(
                    id = item.get("id")?.toString().orEmpty(),
                    movieId = item.get("movie_id")?.toString().orEmpty().ifBlank { movieId },
                    server = item.getString("server").orEmpty().ifBlank { serverName },
                    name = item.getString("name").orEmpty(),
                    slug = item.getString("slug").orEmpty(),
                    type = item.getString("type").orEmpty(),
                    link = link,
                    subtitles = subtitles
                )
            }
        }

        if (servers.values.sumOf { list -> list.count { it.isHls } } == 0) {
            throw IOException("Movie provider returned no HLS episodes")
        }

        return MovieEpisodeCatalog(
            providerId = provider.id,
            movieId = movieId,
            servers = servers.mapValues { it.value.toList() }
        )
    }

    private fun parseSubtitles(raw: String?): List<MovieSubtitle> {
        if (raw.isNullOrBlank() || raw.equals("null", ignoreCase = true)) return emptyList()
        return runCatching {
            val array = JsonParser.`array`().from(raw)
            buildList {
                for (index in 0 until array.size) {
                    val item = array.getObject(index) ?: continue
                    val url = item.getString("url").orEmpty().trim()
                    if (url.isBlank()) continue
                    add(
                        MovieSubtitle(
                            language = item.getString("lang"),
                            label = item.getString("label"),
                            url = url
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private data class PageResult(val url: String, val html: String)

    private fun fetchMoviePage(url: String): PageResult {
        if (!canHandle(url)) throw IOException("Unsupported movie provider URL")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Movie page HTTP ${response.code}")
            }
            return PageResult(
                url = response.request.url.toString(),
                html = response.body?.string().orEmpty()
            )
        }
    }

    internal fun extractMovieId(html: String): String? {
        val patterns = listOf(
            Regex("""episodes\?movie_id=(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""["']movie_id["']\s*:\s*["']?(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""["']movieId["']\s*:\s*["']?(\d+)""", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(html)?.groupValues?.getOrNull(1)
        }
    }

    internal fun extractTitle(html: String): String? {
        val og = Regex(
            """<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
        if (!og.isNullOrBlank()) return decodeBasicEntities(og)

        val title = Regex(
            """<title[^>]*>(.*?)</title>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html)?.groupValues?.getOrNull(1)
        return title?.replace(Regex("""\s+"""), " ")?.trim()?.let(::decodeBasicEntities)
    }

    private fun get(url: String, referer: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json,text/plain,*/*")
            .header("Referer", referer)
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Movie episode API HTTP ${response.code}")
            }
            return response.body?.string()
                ?: throw IOException("Movie episode API returned empty body")
        }
    }

    private fun decodeBasicEntities(value: String): String =
        value.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
    }
}
