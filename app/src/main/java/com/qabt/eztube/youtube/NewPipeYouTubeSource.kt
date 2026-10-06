package com.qabt.eztube.youtube

import com.qabt.eztube.playback.AudioStream
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

class NewPipeYouTubeSource : YouTubeSource {
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
                    thumbnailUrl = item.thumbnails.firstOrNull()?.url,
                    channelUrl = item.uploaderUrl
                )
            }
            .toList()
    }

    override suspend fun channel(channelUrl: String): ChannelSummary {
        val service = ServiceList.YouTube
        val info = ChannelInfo.getInfo(service, channelUrl)
        val videosTab = info.tabs.firstOrNull { handler ->
            handler.contentFilters.any { it.equals("videos", ignoreCase = true) }
        } ?: info.tabs.firstOrNull()

        val videos = videosTab?.let { handler ->
            ChannelTabInfo.getInfo(service, handler).relatedItems
                .asSequence()
                .filterIsInstance<StreamInfoItem>()
                .filterNot { it.isShortFormContent }
                .map { item ->
                    MediaSummary(
                        id = item.url,
                        title = item.name,
                        channel = item.uploaderName.orEmpty().ifBlank { info.name },
                        thumbnailUrl = item.thumbnails.firstOrNull()?.url,
                        channelUrl = item.uploaderUrl ?: info.url
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
            videos = videos
        )
    }

    override suspend fun audioStreams(mediaId: String): List<AudioStream> {
        val info = StreamInfo.getInfo(mediaId)

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
        if (audioOnly.isNotEmpty()) return audioOnly

        // YouTube can enforce SABR for some content (notably made-for-kids videos),
        // leaving no separate audio-only formats. Fall back to the lowest-bandwidth
        // progressive muxed stream so playback still works. Media3 will render audio only.
        return info.videoStreams
            .asSequence()
            .filter { it.isUrl && it.content.isNotBlank() }
            .sortedWith(compareBy({ it.height.takeIf { h -> h > 0 } ?: Int.MAX_VALUE }, { it.bitrate }))
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
    }
}
