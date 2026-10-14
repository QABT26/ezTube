package com.qabt.eztube.youtube

import com.qabt.eztube.playback.AudioStream
import com.qabt.eztube.playback.VideoQuality
import com.qabt.eztube.playback.sabr.SabrSourceSpec
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelTabInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.time.Instant
import java.time.temporal.ChronoUnit

class NewPipeYouTubeSource : YouTubeSource {
    private companion object {
        const val MAX_EXTRA_PAGES = 20
        const val PLAYBACK_STREAM_CACHE_TTL_MS = 120_000L
        const val PLAYBACK_STREAM_CACHE_MAX = 4
        private val extractionLock = Any()
    }

    private data class PlaybackStreams(
        val audio: List<AudioStream>,
        val video: List<AudioStream>,
        val createdAtMs: Long
    )

    private val playbackStreamCache = object : LinkedHashMap<String, PlaybackStreams>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, PlaybackStreams>?
        ): Boolean = size > PLAYBACK_STREAM_CACHE_MAX
    }

    private val playbackCacheLock = Any()

    fun invalidatePlaybackStreams(mediaId: String) {
        synchronized(playbackCacheLock) {
            playbackStreamCache.remove(mediaId)
        }
    }

    private suspend fun playbackStreams(mediaId: String): PlaybackStreams {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(playbackCacheLock) {
            playbackStreamCache[mediaId]?.let { cached ->
                if (now - cached.createdAtMs <= PLAYBACK_STREAM_CACHE_TTL_MS) {
                    return cached
                }
                playbackStreamCache.remove(mediaId)
            }
        }

        val info = streamInfoForClient(mediaId, "visionos")

        val audioOnly = info.audioStreams.mapNotNull { stream ->
            val url = stream.content.takeIf { stream.isUrl && it.isNotBlank() }
                ?: return@mapNotNull null
            val bitrate = stream.averageBitrate
                .takeIf { it > 0 }
                ?: stream.bitrate.takeIf { it > 0 }

            AudioStream(
                url = url,
                bitrateKbps = bitrate,
                codec = stream.codec,
                mimeType = stream.format?.mimeType,
                isFallbackMuxed = false
            )
        }

        val fallbackAudio = if (audioOnly.isEmpty()) {
            info.videoStreams
                .asSequence()
                .filter { it.isUrl && it.content.isNotBlank() }
                .sortedWith(
                    compareBy(
                        { it.height.takeIf { h -> h > 0 } ?: Int.MAX_VALUE },
                        { it.bitrate }
                    )
                )
                .take(1)
                .map { stream ->
                    AudioStream(
                        url = stream.content,
                        bitrateKbps = null,
                        codec = null,
                        mimeType = stream.format?.mimeType,
                        isFallbackMuxed = true
                    )
                }
                .toList()
        } else {
            emptyList()
        }

        val bestAudio = info.audioStreams
            .asSequence()
            .filter { it.isUrl && it.content.isNotBlank() }
            .maxByOrNull {
                it.averageBitrate.takeIf { bitrate -> bitrate > 0 }
                    ?: it.bitrate.takeIf { bitrate -> bitrate > 0 }
                    ?: 0
            }

        val videos = (info.videoStreams + info.videoOnlyStreams)
            .asSequence()
            .filter { it.isUrl && it.content.isNotBlank() }
            .sortedWith(
                compareByDescending<org.schabi.newpipe.extractor.stream.VideoStream> {
                    it.height.takeIf { h -> h > 0 } ?: 0
                }.thenByDescending { it.bitrate }
            )
            .mapNotNull { stream ->
                val needsAudio = stream.isVideoOnly
                if (needsAudio && bestAudio == null) return@mapNotNull null

                AudioStream(
                    url = stream.content,
                    bitrateKbps = stream.bitrate.takeIf { it > 0 },
                    codec = null,
                    mimeType = stream.format?.mimeType,
                    isFallbackMuxed = !needsAudio,
                    videoHeight = stream.height.takeIf { it > 0 },
                    companionAudioUrl = if (needsAudio) bestAudio?.content else null,
                    companionAudioMimeType = if (needsAudio) bestAudio?.format?.mimeType else null
                )
            }
            .distinctBy { stream ->
                listOf(
                    stream.videoHeight?.toString().orEmpty(),
                    stream.mimeType.orEmpty(),
                    stream.companionAudioUrl?.let { "split" } ?: "muxed"
                ).joinToString("|")
            }
            .toList()

        return PlaybackStreams(
            audio = if (audioOnly.isNotEmpty()) audioOnly else fallbackAudio,
            video = videos,
            createdAtMs = android.os.SystemClock.elapsedRealtime()
        ).also { resolved ->
            synchronized(playbackCacheLock) {
                playbackStreamCache[mediaId] = resolved
            }
        }
    }

    private fun streamInfoForClient(mediaId: String, client: String): StreamInfo =
        synchronized(extractionLock) {
            val previous = NewPipe.getYoutubePlayerClient()
            try {
                NewPipe.setYoutubePlayerClient(client)
                StreamInfo.getInfo(mediaId)
            } finally {
                NewPipe.setYoutubePlayerClient(previous)
            }
        }

    fun sabrSpec(mediaId: String, quality: VideoQuality): SabrSourceSpec? {
        // SABR needs a PO-token resolver. EzTubeApplication installs the local
        // DOM resolver before playback is allowed to enter this path.
        if (NewPipe.getYoutubePoTokenResolver() == null) {
            return null
        }

        val info = streamInfoForClient(mediaId, "mweb")

        val sabrInfo = (info.audioStreams.asSequence()
                + info.videoOnlyStreams.asSequence()
                + info.videoStreams.asSequence())
            .firstNotNullOfOrNull { stream ->
                if (stream.deliveryMethod == DeliveryMethod.SABR) {
                    stream.deliveryMethodInfo as? YoutubeSabrInfo
                } else {
                    null
                }
            }
            ?: return null

        val audioCandidates = sabrInfo.formats
            .asSequence()
            .filter { it.isAudio && !it.isDrc }
            .toList()
        if (audioCandidates.isEmpty()) return null

        val originalAudio = audioCandidates.filter {
            it.isOriginalAudio || it.audioTrackId.isNullOrBlank()
        }.ifEmpty { audioCandidates }

        val audio = originalAudio
            .filter { it.mimeType?.contains("mp4a", ignoreCase = true) == true }
            .maxByOrNull { it.bitrate }
            ?: originalAudio.maxByOrNull { it.bitrate }
            ?: return null

        val maxHeight = 1080
        val allVideos = sabrInfo.formats
            .asSequence()
            .filter { it.isVideo && it.height > 0 }
            .toList()
        if (allVideos.isEmpty()) return null

        val cappedVideos = allVideos
            .filter { it.height <= maxHeight }
            .ifEmpty {
                listOf(
                    allVideos.minBy {
                        kotlin.math.abs(it.height - maxHeight)
                    }
                )
            }

        fun codecFamily(format: YoutubeSabrInfo.Format): String {
            val mime = format.mimeType.orEmpty().lowercase()
            return when {
                "avc1" in mime -> "avc1"
                "vp09" in mime || "vp9" in mime -> "vp9"
                "av01" in mime -> "av01"
                else -> mime.substringBefore(';')
            }
        }

        // Do not pick AVC merely because one AVC representation exists. Pick the
        // codec family that exposes the strongest 360/480/720/1080 ladder, then
        // prefer AVC only on an otherwise equal ladder for broad decoder support.
        val standardHeights = listOf(360, 480, 720, 1080)
        val families = cappedVideos.groupBy(::codecFamily)
        val preferredFamily = families.entries
            .maxWithOrNull(
                compareBy<Map.Entry<String, List<YoutubeSabrInfo.Format>>> { entry ->
                    standardHeights.count { target ->
                        entry.value.any { it.height == target }
                    }
                }
                    .thenBy { entry -> entry.value.maxOfOrNull { it.height } ?: 0 }
                    .thenBy { entry -> entry.value.map { it.height }.distinct().size }
                    .thenBy { entry -> if (entry.key == "avc1") 1 else 0 }
            )
            ?.key
            ?: codecFamily(cappedVideos.first())

        val familyVideos = cappedVideos.filter { codecFamily(it) == preferredFamily }

        // Multiple SABR representations can report the same resolution. Keep the
        // strongest representation at each height instead of whichever one happens
        // to arrive first from the extractor.
        val adaptiveVideos = familyVideos
            .groupBy { it.height }
            .values
            .mapNotNull { sameHeight ->
                sameHeight.maxWithOrNull(
                    compareBy<YoutubeSabrInfo.Format> { it.bitrate }
                        .thenBy { it.width }
                )
            }
            .sortedBy { it.height }

        if (adaptiveVideos.isEmpty()) return null

        fun bestForTarget(target: Int): YoutubeSabrInfo.Format =
            adaptiveVideos
                .filter { it.height <= target }
                .maxWithOrNull(
                    compareBy<YoutubeSabrInfo.Format> { it.height }
                        .thenBy { it.bitrate }
                )
                ?: adaptiveVideos.minWithOrNull(
                    compareBy<YoutubeSabrInfo.Format> {
                        kotlin.math.abs(it.height - target)
                    }.thenByDescending { it.bitrate }
                )
                ?: adaptiveVideos.first()

        // Explicit quality starts directly at that rung. AUTO starts at the best
        // available <=1080 rather than the old lowest-rung bootstrap that caused
        // the first seconds to look blurry.
        val bootstrapVideo = quality.targetHeight
            ?.let(::bestForTarget)
            ?: adaptiveVideos.maxWithOrNull(
                compareBy<YoutubeSabrInfo.Format> { it.height }
                    .thenBy { it.bitrate }
            )
            ?: adaptiveVideos.first()

        return SabrSourceSpec(
            sabrInfo.videoId,
            sabrInfo,
            audio,
            listOf(audio),
            adaptiveVideos,
            bootstrapVideo
        )
    }

    private fun relativeUploadDate(item: StreamInfoItem): String? {
        val instant = item.uploadDate?.instant ?: return null
        val now = Instant.now()
        val safeInstant = if (instant.isAfter(now)) now else instant
        val seconds = ChronoUnit.SECONDS.between(safeInstant, now).coerceAtLeast(0L)
        val language = NewPipe.getPreferredLocalization().languageCode.lowercase()

        val (value, unit) = when {
            seconds < 60L -> 0L to "minute"
            seconds < 3_600L -> (seconds / 60L).coerceAtLeast(1L) to "minute"
            seconds < 86_400L -> (seconds / 3_600L).coerceAtLeast(1L) to "hour"
            seconds < 604_800L -> (seconds / 86_400L).coerceAtLeast(1L) to "day"
            seconds < 2_629_800L -> (seconds / 604_800L).coerceAtLeast(1L) to "week"
            seconds < 31_557_600L -> (seconds / 2_629_800L).coerceAtLeast(1L) to "month"
            else -> (seconds / 31_557_600L).coerceAtLeast(1L) to "year"
        }

        return if (language == "vi") {
            when (unit) {
                "minute" -> if (value == 0L) "vừa đăng" else "$" + "value phút trước"
                "hour" -> "$" + "value giờ trước"
                "day" -> "$" + "value ngày trước"
                "week" -> "$" + "value tuần trước"
                "month" -> "$" + "value tháng trước"
                else -> "$" + "value năm trước"
            }
        } else {
            fun plural(singular: String): String =
                if (value == 1L) singular else singular + "s"

            when (unit) {
                "minute" -> if (value == 0L) "just now" else "$" + "value " + plural("minute") + " ago"
                "hour" -> "$" + "value " + plural("hour") + " ago"
                "day" -> "$" + "value " + plural("day") + " ago"
                "week" -> "$" + "value " + plural("week") + " ago"
                "month" -> "$" + "value " + plural("month") + " ago"
                else -> "$" + "value " + plural("year") + " ago"
            }
        }
    }

    private fun uploadDateLabel(item: StreamInfoItem): String? =
        relativeUploadDate(item)
            ?: item.textualUploadDate
                ?.trim()
                ?.takeIf { text ->
                    val normalized = text.lowercase()
                    when (NewPipe.getPreferredLocalization().languageCode.lowercase()) {
                        "vi" -> listOf(
                            "trước", "hôm nay", "hôm qua", "phút", "giờ",
                            "ngày", "tuần", "tháng", "năm"
                        ).any(normalized::contains)
                        else -> listOf(
                            "ago", "today", "yesterday", "minute", "hour",
                            "day", "week", "month", "year"
                        ).any(normalized::contains)
                    }
                }

    private fun StreamInfoItem.toSummary(fallbackChannelUrl: String? = null, fallbackChannel: String = "") =
        MediaSummary(
            id = url,
            title = name,
            channel = uploaderName.orEmpty().ifBlank { fallbackChannel },
            thumbnailUrl = thumbnailUrl,
            channelUrl = uploaderUrl ?: fallbackChannelUrl,
            viewCount = viewCount,
            uploadDateText = uploadDateLabel(this),
            durationSeconds = duration
        )

    override suspend fun search(query: String): List<MediaSummary> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return emptyList()

        val service = ServiceList.YouTube
        val factory = service.searchQHFactory
        val contentFilter = factory.availableContentFilter
            .filterGroups
            .asSequence()
            .flatMap { it.filterItems.asSequence() }
            .firstOrNull { it.name.equals("videos", ignoreCase = true) }
            ?: factory.availableContentFilter
                .filterGroups
                .asSequence()
                .flatMap { it.filterItems.asSequence() }
                .firstOrNull { it.name.equals("all", ignoreCase = true) }
            ?: error("YouTube search has no usable content filter")

        val searchHandler = factory.fromQuery(
            normalized,
            listOf(contentFilter),
            null
        )
        return SearchInfo.getInfo(service, searchHandler)
            .relatedItems
            .asSequence()
            .filterIsInstance<StreamInfoItem>()
            .filterNot { it.isShortFormContent }
            .map { item ->
                MediaSummary(
                    id = item.url,
                    title = item.name,
                    channel = item.uploaderName.orEmpty(),
                    thumbnailUrl = item.thumbnailUrl,
                    channelUrl = item.uploaderUrl,
                    viewCount = item.viewCount,
                    uploadDateText = uploadDateLabel(item),
                    durationSeconds = item.duration
                )
            }
            .toList()
    }

    override suspend fun trending(topic: String, language: String): List<MediaSummary> {
        val languageQuery = when (language) {
            "Vietnamese" -> "Việt Nam"
            "English" -> "English"
            "Korean" -> "Korean"
            "Japanese" -> "Japanese"
            else -> ""
        }

        if (topic == "Live") {
            val lives = runCatching {
                val service = ServiceList.YouTube
                val factory = service.kioskList
                    .getListLinkHandlerFactoryByType("Recommended Lives")
                val url = factory.fromId("Recommended Lives").url
                KioskInfo.getInfo(service, url).relatedItems
                    .asSequence()
                    .filterIsInstance<StreamInfoItem>()
                    .filterNot { it.isShortFormContent }
                    .map { it.toSummary() }
                    .take(20)
                    .toList()
            }.getOrDefault(emptyList())
            if (lives.isNotEmpty()) return lives
        }

        val topicQueries = when (topic) {
            "Podcasts" -> listOf(
                "podcast trending",
                "popular podcast",
                "podcast"
            )
            "Gaming" -> listOf(
                "gaming trending",
                "popular gaming",
                "gaming"
            )
            "Movies" -> listOf(
                "movie trailer trending",
                "popular movie trailer",
                "movie trailer"
            )
            "Live" -> listOf(
                "live now",
                "popular live",
                "live"
            )
            else -> listOf(
                "music trending",
                "popular music",
                "top music",
                "music",
                "nhạc"
            )
        }

        val queries = buildList {
            for (base in topicQueries) {
                if (languageQuery.isNotBlank()) add("$languageQuery $base")
                add(base)
            }
        }.distinct()

        val collected = linkedMapOf<String, MediaSummary>()
        for (query in queries) {
            val items = runCatching { search(query) }.getOrDefault(emptyList())
            for (item in items) {
                collected.putIfAbsent(item.id, item)
                if (collected.size >= 20) break
            }
            if (collected.size >= 20) break
        }

        return collected.values.take(20)
    }

    override suspend fun channel(channelUrl: String): ChannelSummary {
        val service = ServiceList.YouTube
        val info = ChannelInfo.getInfo(service, channelUrl)
        val videosTab = info.tabs.firstOrNull { handler ->
            handler.contentFilters.any { it.name.equals("videos", ignoreCase = true) }
        } ?: info.tabs.firstOrNull()

        val playlistsTab = info.tabs.firstOrNull { handler ->
            handler.contentFilters.any { it.name.equals("playlists", ignoreCase = true) }
        }

        val videos = videosTab?.let { handler ->
            val first = ChannelTabInfo.getInfo(service, handler)
            val all = first.relatedItems.toMutableList()
            var next = first.nextPage
            var pages = 0
            while (next != null && pages++ < MAX_EXTRA_PAGES) {
                val page = ChannelTabInfo.getMoreItems(service, handler, next)
                all += page.items
                next = page.nextPage
            }
            all.asSequence()
                .filterIsInstance<StreamInfoItem>()
                .filterNot { it.isShortFormContent }
                .map { it.toSummary(info.url, info.name) }
                .toList()
        }.orEmpty()

        val playlists = playlistsTab?.let { handler ->
            val first = ChannelTabInfo.getInfo(service, handler)
            val all = first.relatedItems.toMutableList()
            var next = first.nextPage
            var pages = 0
            while (next != null && pages++ < MAX_EXTRA_PAGES) {
                val page = ChannelTabInfo.getMoreItems(service, handler, next)
                all += page.items
                next = page.nextPage
            }
            all.asSequence()
                .filterIsInstance<PlaylistInfoItem>()
                .map { item ->
                    PlaylistSummary(
                        url = item.url,
                        title = item.name,
                        thumbnailUrl = item.thumbnailUrl,
                        streamCount = item.streamCount
                    )
                }
                .toList()
        }.orEmpty()

        return ChannelSummary(
            url = info.url,
            name = info.name,
            avatarUrl = info.avatars.firstOrNull()?.url,
            bannerUrl = info.banners.firstOrNull()?.url,
            subscriberCount = info.subscriberCount,
            videos = videos,
            playlists = playlists
        )
    }

    override suspend fun playlist(playlistUrl: String): PlaylistDetail {
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, playlistUrl)
        val all = info.relatedItems.toMutableList()
        var next = info.nextPage
        var pages = 0
        while (next != null && pages++ < MAX_EXTRA_PAGES) {
            val page = PlaylistInfo.getMoreItems(ServiceList.YouTube, playlistUrl, next)
            all += page.items
            next = page.nextPage
        }
        val items = all.asSequence()
            .filterNot { it.isShortFormContent }
            .map { it.toSummary() }
            .toList()
        return PlaylistDetail(
            url = info.url,
            title = info.name,
            thumbnailUrl = info.thumbnailUrl,
            uploaderName = info.uploaderName.orEmpty(),
            items = items
        )
    }

    override suspend fun videoStreams(mediaId: String): List<AudioStream> =
        playbackStreams(mediaId).video

    override suspend fun audioStreams(mediaId: String): List<AudioStream> =
        playbackStreams(mediaId).audio

}
