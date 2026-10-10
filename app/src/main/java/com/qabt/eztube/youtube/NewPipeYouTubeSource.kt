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
        // Current YouTube SABR may require attestation / a PO token. PipePipeClient
        // installs a Local DOM PO-token resolver before enabling SABR. ezTube must
        // not enter SABR until an equivalent resolver is installed; otherwise the
        // first media request can fail immediately with SabrAttestationException
        // and surface as a player source error.
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

        // Keep the full adaptive ladder in one SABR source. The user's selected
        // quality is enforced by Media3 track constraints, so changing 360/480/720/1080
        // does not require rebuilding the source.
        val maxHeight = 1080
        val videoCandidates = sabrInfo.formats
            .asSequence()
            .filter { it.isVideo && it.height > 0 && it.height <= maxHeight }
            .toList()
            .ifEmpty {
                sabrInfo.formats
                    .filter { it.isVideo && it.height > 0 }
                    .sortedBy { kotlin.math.abs(it.height - maxHeight) }
                    .take(1)
            }
        if (videoCandidates.isEmpty()) return null

        fun codecFamily(format: YoutubeSabrInfo.Format): String {
            val mime = format.mimeType.orEmpty().lowercase()
            return when {
                "avc1" in mime -> "avc1"
                "vp09" in mime || "vp9" in mime -> "vp9"
                "av01" in mime -> "av01"
                else -> mime.substringBefore(';')
            }
        }

        val preferredFamily = listOf("avc1", "vp9", "av01")
            .firstOrNull { family ->
                videoCandidates.any { codecFamily(it) == family }
            }
            ?: codecFamily(videoCandidates.first())

        val adaptiveVideos = videoCandidates
            .filter { codecFamily(it) == preferredFamily }
            .distinctBy { it.height }
            .sortedBy { it.height }
            .ifEmpty { listOf(videoCandidates.minBy { it.height }) }

        val bootstrapVideo = quality.targetHeight?.let { target ->
            adaptiveVideos
                .filter { it.height <= target }
                .maxWithOrNull(
                    compareBy<YoutubeSabrInfo.Format> { it.height }
                        .thenBy { it.bitrate }
                )
                ?: adaptiveVideos.minByOrNull {
                    kotlin.math.abs(it.height - target)
                }
        } ?: adaptiveVideos.first()

        return SabrSourceSpec(
            sabrInfo.videoId,
            sabrInfo,
            audio,
            listOf(audio),
            adaptiveVideos,
            bootstrapVideo
        )
    }

    private fun StreamInfoItem.toSummary(fallbackChannelUrl: String? = null, fallbackChannel: String = "") =
        MediaSummary(
            id = url,
            title = name,
            channel = uploaderName.orEmpty().ifBlank { fallbackChannel },
            thumbnailUrl = thumbnailUrl,
            channelUrl = uploaderUrl ?: fallbackChannelUrl,
            viewCount = viewCount,
            uploadDateText = textualUploadDate,
            durationSeconds = duration
        )

    override suspend fun search(query: String): List<MediaSummary> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return emptyList()

        val service = ServiceList.YouTube
        val searchHandler = service.searchQHFactory.fromQuery(normalized)
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
                    uploadDateText = item.textualUploadDate,
                    durationSeconds = item.duration
                )
            }
            .toList()
    }

    override suspend fun trending(topic: String, language: String): List<MediaSummary> {
        val service = ServiceList.YouTube

        // YouTube removed the old general Trending page in July 2025.
        // Prefer the still-supported Music chart for this audio-first app, then
        // fall back through other supported kiosks instead of returning nothing.
        val preferred = when (topic) {
            "Podcasts" -> "trending_podcasts_episodes"
            "Gaming" -> "trending_gaming"
            "Movies" -> "trending_movies_and_shows"
            "Live" -> "live"
            else -> "trending_music"
        }
        val kioskIds = listOf(preferred, "trending_music", "trending_podcasts_episodes", "trending_gaming", "trending_movies_and_shows", "live").distinct()

        for (kioskId in kioskIds) {
            val items = runCatching {
                val factory = service.kioskList.getListLinkHandlerFactoryByType(kioskId)
                val url = factory.fromId(kioskId).url
                KioskInfo.getInfo(service, url).relatedItems
                    .asSequence()
                    .filterIsInstance<StreamInfoItem>()
                    .filterNot { it.isShortFormContent }
                    .map { it.toSummary() }
                    .take(20)
                    .toList()
            }.getOrDefault(emptyList())

            if (items.isNotEmpty()) {
                if (language == "All") return items
                val languageQuery = when (language) { "Vietnamese" -> "Việt Nam"; "English" -> "English"; "Korean" -> "Korean"; "Japanese" -> "Japanese"; else -> language }
                val localized = runCatching { search("$languageQuery $topic trending") }.getOrDefault(emptyList())
                return (localized + items).distinctBy { it.id }.take(20)
            }
        }

        // Kiosk endpoints are not stable across YouTube client changes. Never leave
        // Home empty just because the current extractor/client lost a kiosk route.
        val languageQuery = when (language) {
            "Vietnamese" -> "Việt Nam"
            "English" -> "English"
            "Korean" -> "Korean"
            "Japanese" -> "Japanese"
            else -> ""
        }
        val topicQuery = when (topic) {
            "Podcasts" -> "podcast"
            "Gaming" -> "gaming"
            "Movies" -> "movie trailer"
            "Live" -> "live"
            else -> "music"
        }
        val fallbackQueries = buildList {
            if (languageQuery.isNotBlank()) {
                add("$languageQuery $topicQuery trending")
                add("$languageQuery $topicQuery popular")
            }
            add("$topicQuery trending")
            add("$topicQuery popular")
        }.distinct()

        for (query in fallbackQueries) {
            val items = runCatching { search(query) }.getOrDefault(emptyList())
            if (items.isNotEmpty()) return items.take(20)
        }

        return emptyList()
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
