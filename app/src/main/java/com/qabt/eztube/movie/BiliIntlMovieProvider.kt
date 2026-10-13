package com.qabt.eztube.movie

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class BiliIntlMovieProvider(
    private val client: OkHttpClient? = null
) : MovieProvider {
    override val id: String = "biliintl"
    override val displayName: String = "Bilibili"

    private val http = client ?: OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun search(query: String): List<MovieCatalogItem> {
        val normalized = query.trim()
        if (normalized.isBlank()) return emptyList()

        parseVideoUrl(normalized)?.let { parsed ->
            val meta = fetchMetadata(parsed.aid, parsed.pageUrl)
            return listOf(
                MovieCatalogItem(
                    providerId = id,
                    providerName = displayName,
                    slug = parsed.aid,
                    title = meta.title.ifBlank { "Bilibili video ${parsed.aid}" },
                    originalTitle = null,
                    thumbnailUrl = meta.thumbnail,
                    year = null,
                    language = null,
                    durationText = null,
                    durationSeconds = null,
                    episodeBadge = null,
                    isSeries = false
                )
            )
        }

        return searchByTitle(normalized)
    }

    private fun searchByTitle(query: String): List<MovieCatalogItem> {
        val url = "$API_BASE/web/v2/search_v2".toHttpUrl()
            .newBuilder()
            .addQueryParameter("keyword", query)
            .addQueryParameter("platform", "web")
            .addQueryParameter("s_locale", "vi_VN")
            .addQueryParameter("highlight", "1")
            .addQueryParameter("pn", "1")
            .addQueryParameter("ps", "20")
            .addQueryParameter("qid", "")
            .addQueryParameter("sort", "0")
            .addQueryParameter("duration_type", "0")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "$WEB_BASE/")
            .get()
            .build()

        val root = http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Bilibili search HTTP ${response.code}")
            }
            if (body.isBlank()) {
                throw IOException("Bilibili search returned empty body")
            }
            runCatching { JsonParser.`object`().from(body) }
                .getOrElse { throw IOException("Bilibili search returned invalid JSON", it) }
        }

        val code = root.getInt("code")
        if (code != 0) {
            val message = root.getString("message").orEmpty()
            throw IOException(
                "Bilibili search API error $code" +
                    message.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            )
        }

        val data = root.getObject("data") ?: return emptyList()
        val modules = data.getArray("modules") ?: return emptyList()
        val results = mutableListOf<MovieCatalogItem>()

        for (moduleIndex in 0 until modules.size) {
            val module = modules.getObject(moduleIndex) ?: continue
            if (!module.getString("type").orEmpty().equals("ugc", ignoreCase = true)) {
                continue
            }

            val items = module.getArray("items") ?: continue
            for (itemIndex in 0 until items.size) {
                val item = items.getObject(itemIndex) ?: continue
                val aid = item.get("aid")?.toString()?.trim().orEmpty()
                if (!aid.matches(Regex("""\d{1,20}"""))) continue

                val title = cleanSearchTitle(item.getString("title").orEmpty())
                if (title.isBlank()) continue

                val author = item.getObject("author")
                    ?.getString("nickname")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

                val rawDuration = item.get("duration")
                val durationText = when (rawDuration) {
                    is String -> rawDuration.trim().takeIf { it.isNotBlank() }
                    is Number -> formatClockDuration(rawDuration.toLong())
                    else -> null
                }
                val durationSeconds = when (rawDuration) {
                    is Number -> rawDuration.toLong().takeIf { it > 0L }
                    is String -> parseClockDuration(rawDuration)
                    else -> null
                }

                results += MovieCatalogItem(
                    providerId = id,
                    providerName = displayName,
                    slug = aid,
                    title = title,
                    originalTitle = author,
                    thumbnailUrl = normalizeBiliImage(item.getString("cover")),
                    year = null,
                    language = null,
                    durationText = durationText,
                    durationSeconds = durationSeconds,
                    episodeBadge = null,
                    isSeries = false
                )
            }
        }

        return results
            .distinctBy { it.slug }
            .take(20)
    }


    override fun detail(slug: String): MovieDetail {
        requireAid(slug)
        val pageUrl = "$WEB_BASE/en/video/$slug"
        val meta = fetchMetadata(slug, pageUrl)
        // Validate availability now, but do not keep temporary CDN URLs.
        val play = fetchPlayUrl(slug)

        if (play.videoStreams.isEmpty() || play.audioUrl.isNullOrBlank()) {
            throw IOException("Bilibili returned no playable video/audio pair")
        }

        val catalog = MovieCatalogItem(
            providerId = id,
            providerName = displayName,
            slug = slug,
            title = meta.title.ifBlank { "Bilibili video $slug" },
            originalTitle = null,
            thumbnailUrl = meta.thumbnail,
            year = null,
            language = null,
            durationText = null,
            durationSeconds = null,
            episodeBadge = null,
            isSeries = false
        )

        val episode = MovieEpisodeRef(
            providerId = id,
            providerName = displayName,
            movieSlug = slug,
            movieTitle = catalog.title,
            serverIndex = 0,
            episodeIndex = 0,
            serverName = "Bilibili",
            episodeSlug = "video",
            episodeName = "Video",
            thumbnailUrl = catalog.thumbnailUrl,
            durationSeconds = null,
            language = null
        )

        return MovieDetail(catalog = catalog, episodes = listOf(episode))
    }

    override fun resolve(mediaId: String): MoviePlayback {
        val parts = mediaId.split(':')
        if (parts.size != 5 || parts[0] != "movie" || parts[1] != id) {
            throw IOException("Unsupported Bilibili media id")
        }
        val aid = parts[2]
        requireAid(aid)

        // Resolve on every play because Bilibili CDN URLs are temporary.
        val play = fetchPlayUrl(aid)
        if (play.videoStreams.isEmpty()) {
            throw IOException("Bilibili returned no video streams")
        }
        val audioUrl = play.audioUrl
            ?: throw IOException("Bilibili returned no audio stream")

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$WEB_BASE/"
        )
        val probeVideo = play.videoStreams.maxByOrNull { it.height ?: 0 }
            ?: throw IOException("Bilibili returned no video streams")
        val videoHttp = probeMedia(probeVideo.url, headers, "video")
        val audioHttp = probeMedia(audioUrl, headers, "audio")
        val subtitles = runCatching { fetchSubtitles(aid) }.getOrDefault(emptyList())

        return MoviePlayback(
            mediaId = mediaId,
            videoStreams = play.videoStreams,
            audioUrl = audioUrl,
            audioMimeType = play.audioMimeType,
            subtitles = subtitles,
            requestHeaders = headers,
            diagnostic = buildString {
                append("Provider: Bilibili")
                append("\nAID: ").append(aid)
                append("\nPlay API: OK")
                append("\nVideo streams: ").append(play.videoStreams.size)
                append("\nVideo CDN: HTTP ").append(videoHttp)
                append("\nAudio CDN: HTTP ").append(audioHttp)
                append("\nSubtitles: ").append(subtitles.size)
            }
        )
    }

    private data class ParsedVideo(
        val aid: String,
        val pageUrl: String
    )

    private data class Metadata(
        val title: String,
        val thumbnail: String?
    )

    private data class PlayResult(
        val videoStreams: List<MovieVideoStream>,
        val audioUrl: String?,
        val audioMimeType: String?
    )

    private fun parseVideoUrl(value: String): ParsedVideo? {
        val match = VIDEO_URL_REGEX.find(value) ?: return null
        val aid = match.groupValues[1]
        return ParsedVideo(
            aid = aid,
            pageUrl = "$WEB_BASE/en/video/$aid"
        )
    }

    private fun fetchMetadata(aid: String, pageUrl: String): Metadata {
        val request = Request.Builder()
            .url(pageUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "vi,en-US;q=0.9,en;q=0.8")
            .header("Referer", "$WEB_BASE/")
            .get()
            .build()

        return http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Bilibili page HTTP ${response.code}")
            }
            Metadata(
                title = extractMeta(body, "og:title")
                    ?: extractHtmlTitle(body)
                    ?: "Bilibili video $aid",
                thumbnail = extractMeta(body, "og:image")
            )
        }
    }

    private fun fetchPlayUrl(aid: String): PlayResult {
        val url = "$API_BASE/web/playurl".toHttpUrl()
            .newBuilder()
            .addQueryParameter("platform", "web")
            .addQueryParameter("aid", aid)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "$WEB_BASE/")
            .get()
            .build()

        val root = http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Bilibili play API HTTP ${response.code}")
            }
            if (body.isBlank()) {
                throw IOException("Bilibili play API returned empty body")
            }
            runCatching { JsonParser.`object`().from(body) }
                .getOrElse { throw IOException("Bilibili play API returned invalid JSON", it) }
        }

        val code = root.getInt("code")
        if (code != 0) {
            val message = root.getString("message").orEmpty()
            when (code) {
                10004001 -> throw IOException("Bilibili: not available in this region")
                10004004, 10004005, 10023006 ->
                    throw IOException("Bilibili: login required")
                else -> throw IOException(
                    "Bilibili API error $code" +
                        message.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                )
            }
        }

        val data = root.getObject("data")
            ?: throw IOException("Bilibili play API has no data")
        val playurl = data.getObject("playurl")
            ?: throw IOException("Bilibili play API has no playurl")

        val streams = mutableListOf<MovieVideoStream>()
        val videos = playurl.getArray("video")
        if (videos != null) {
            for (index in 0 until videos.size) {
                val video = videos.getObject(index) ?: continue
                val resource = video.getObject("video_resource") ?: continue
                val urlValue = resource.getString("url").orEmpty().trim()
                if (urlValue.isBlank()) continue

                streams += MovieVideoStream(
                    url = urlValue,
                    mimeType = resource.getString("mimetype")
                        ?.takeIf { it.isNotBlank() }
                        ?: "video/mp4",
                    width = resource.getInt("width").takeIf { it > 0 },
                    height = resource.getInt("height").takeIf { it > 0 },
                    bitrate = resource.getInt("bandwidth").takeIf { it > 0 },
                    codecs = resource.getString("codecs")
                )
            }
        }

        val audios = playurl.getArray("audio_resource")
        var bestAudio: JsonObject? = null
        if (audios != null) {
            for (index in 0 until audios.size) {
                val candidate = audios.getObject(index) ?: continue
                val candidateUrl = candidate.getString("url").orEmpty().trim()
                if (candidateUrl.isBlank()) continue
                val currentBitrate = bestAudio?.getInt("bandwidth") ?: -1
                val bitrate = candidate.getInt("bandwidth")
                if (bestAudio == null || bitrate > currentBitrate) {
                    bestAudio = candidate
                }
            }
        }

        return PlayResult(
            videoStreams = streams.distinctBy { it.url },
            audioUrl = bestAudio?.getString("url")?.trim()?.takeIf { it.isNotBlank() },
            audioMimeType = bestAudio?.getString("mimetype")
                ?.takeIf { it.isNotBlank() }
                ?: "audio/mp4"
        )
    }

    private fun fetchSubtitles(aid: String): List<MovieSubtitle> {
        val url = "$API_BASE/web/v2/subtitle".toHttpUrl()
            .newBuilder()
            .addQueryParameter("platform", "web")
            .addQueryParameter("s_locale", "vi_VN")
            .addQueryParameter("aid", aid)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "$WEB_BASE/")
            .get()
            .build()

        val root = http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Bilibili subtitle API HTTP ${response.code}")
            }
            if (body.isBlank()) return emptyList()
            runCatching { JsonParser.`object`().from(body) }.getOrNull()
                ?: return emptyList()
        }

        if (root.getInt("code") != 0) return emptyList()
        val data = root.getObject("data") ?: return emptyList()
        val arrays = listOfNotNull(
            data.getArray("subtitles"),
            data.getArray("video_subtitle")
        )

        val results = mutableListOf<MovieSubtitle>()
        arrays.forEach { array ->
            for (index in 0 until array.size) {
                val sub = array.getObject(index) ?: continue
                val language = (
                    sub.getString("lang_key")
                        ?: sub.getString("language")
                        ?: sub.getString("lang")
                    )
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                val label = (
                    sub.getString("lang")
                        ?: sub.getString("label")
                        ?: sub.getString("name")
                        ?: language
                    )
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

                val direct = buildList {
                    sub.getObject("srt")?.getString("url")?.let(::add)
                    sub.getObject("ass")?.getString("url")?.let(::add)
                    sub.getString("url")?.let(::add)
                }
                    .mapNotNull(::normalizeSubtitleUrl)
                    .distinct()

                var added = false
                direct.forEach { subtitleUrl ->
                    val lower = subtitleUrl.substringBefore('?').lowercase()
                    when {
                        lower.endsWith(".srt") -> {
                            results += MovieSubtitle(
                                language = language,
                                label = label,
                                url = subtitleUrl,
                                mimeType = "application/x-subrip"
                            )
                            added = true
                        }
                        lower.endsWith(".ass") || lower.endsWith(".ssa") -> {
                            results += MovieSubtitle(
                                language = language,
                                label = label,
                                url = subtitleUrl,
                                mimeType = "text/x-ssa"
                            )
                            added = true
                        }
                        lower.endsWith(".vtt") -> {
                            results += MovieSubtitle(
                                language = language,
                                label = label,
                                url = subtitleUrl,
                                mimeType = "text/vtt"
                            )
                            added = true
                        }
                    }
                }

                if (!added) {
                    direct.firstOrNull()?.let { jsonUrl ->
                        subtitleJsonToDataUri(jsonUrl)?.let { dataUri ->
                            results += MovieSubtitle(
                                language = language,
                                label = label,
                                url = dataUri,
                                mimeType = "application/x-subrip"
                            )
                        }
                    }
                }
            }
        }

        return results
            .distinctBy { "${it.language.orEmpty()}|${it.label.orEmpty()}|${it.url}" }
            .sortedWith(
                compareBy<MovieSubtitle> {
                    when {
                        it.language.orEmpty().startsWith("vi", ignoreCase = true) ||
                            it.label.orEmpty().contains("việt", ignoreCase = true) -> 0
                        it.language.orEmpty().startsWith("en", ignoreCase = true) ||
                            it.label.orEmpty().contains("english", ignoreCase = true) -> 1
                        else -> 2
                    }
                }.thenBy { it.label.orEmpty() }
            )
    }

    private fun normalizeSubtitleUrl(value: String): String? {
        val raw = value.trim()
        if (raw.isBlank()) return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            else -> null
        }
    }

    private fun subtitleJsonToDataUri(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "$WEB_BASE/")
            .get()
            .build()

        val root = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            runCatching { JsonParser.`object`().from(body) }.getOrNull()
        } ?: return null

        val body = root.getArray("body") ?: return null
        val srt = buildString {
            var sequence = 1
            for (index in 0 until body.size) {
                val line = body.getObject(index) ?: continue
                val text = line.getString("content").orEmpty().trim()
                val from = line.getDouble("from")
                val to = line.getDouble("to")
                if (text.isBlank() || to <= from || from < 0.0) continue

                append(sequence++).append('\n')
                append(formatSrtTime(from))
                    .append(" --> ")
                    .append(formatSrtTime(to))
                    .append('\n')
                append(text.replace("\\N", "\n")).append("\n\n")
            }
        }
        if (srt.isBlank()) return null

        val encoded = java.util.Base64.getEncoder()
            .encodeToString(srt.toByteArray(Charsets.UTF_8))
        return "data:application/x-subrip;base64,$encoded"
    }

    private fun formatSrtTime(seconds: Double): String {
        val millis = (seconds * 1000.0).toLong().coerceAtLeast(0L)
        val hours = millis / 3_600_000L
        val minutes = (millis % 3_600_000L) / 60_000L
        val secs = (millis % 60_000L) / 1_000L
        val ms = millis % 1_000L
        return "%02d:%02d:%02d,%03d".format(hours, minutes, secs, ms)
    }

    private fun probeMedia(
        url: String,
        headers: Map<String, String>,
        label: String
    ): Int {
        val builder = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-1023")
            .get()
        headers.forEach { (name, value) -> builder.header(name, value) }

        return http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                throw IOException("Bilibili $label CDN HTTP ${response.code}")
            }
            response.code
        }
    }

    private fun cleanSearchTitle(value: String): String =
        htmlDecode(
            value.replace(Regex("""<[^>]+>"""), "")
        ).replace(Regex("""\s+"""), " ").trim()

    private fun normalizeBiliImage(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            else -> raw
        }
    }

    private fun formatClockDuration(seconds: Long): String? {
        if (seconds <= 0L) return null
        val hours = seconds / 3600L
        val minutes = (seconds % 3600L) / 60L
        val secs = seconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, secs)
        } else {
            "%d:%02d".format(minutes, secs)
        }
    }

    private fun parseClockDuration(value: String?): Long? {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return null
        val parts = text.split(':').mapNotNull { it.toLongOrNull() }
        if (parts.size !in 2..3) return null
        if (parts.drop(1).any { it !in 0..59 }) return null
        return parts.fold(0L) { total, part -> total * 60L + part }
    }

    private fun extractMeta(body: String, property: String): String? {
        META_PROPERTY_FIRST.find(body)?.let { match ->
            if (htmlDecode(match.groupValues[1]).equals(property, ignoreCase = true)) {
                return htmlDecode(match.groupValues[2]).trim().takeIf { it.isNotBlank() }
            }
        }
        META_CONTENT_FIRST.find(body)?.let { match ->
            if (htmlDecode(match.groupValues[2]).equals(property, ignoreCase = true)) {
                return htmlDecode(match.groupValues[1]).trim().takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun extractHtmlTitle(body: String): String? =
        TITLE_REGEX.find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::htmlDecode)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun htmlDecode(value: String): String =
        value
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

    private fun requireAid(value: String) {
        if (!value.matches(Regex("""\d{6,20}"""))) {
            throw IOException("Invalid Bilibili video id")
        }
    }

    private companion object {
        const val WEB_BASE = "https://www.bilibili.tv"
        const val API_BASE = "https://api.bilibili.tv/intl/gateway"

        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

        val VIDEO_URL_REGEX = Regex(
            """https?://(?:www\.)?(?:bilibili\.tv|biliintl\.com)/(?:[a-zA-Z]{2}/)?video/(\d+)""",
            RegexOption.IGNORE_CASE
        )

        val META_PROPERTY_FIRST = Regex(
            """<meta[^>]+(?:property|name)=["']([^"']+)["'][^>]+content=["']([^"']*)["'][^>]*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val META_CONTENT_FIRST = Regex(
            """<meta[^>]+content=["']([^"']*)["'][^>]+(?:property|name)=["']([^"']+)["'][^>]*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        val TITLE_REGEX = Regex(
            """<title[^>]*>(.*?)</title>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
    }
}
