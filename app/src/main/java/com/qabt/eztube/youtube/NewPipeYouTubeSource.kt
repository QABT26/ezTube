package com.qabt.eztube.youtube

import com.qabt.eztube.playback.AudioStream
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
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
                    thumbnailUrl = item.thumbnails.firstOrNull()?.url
                )
            }
            .toList()
    }

    override suspend fun audioStreams(mediaId: String): List<AudioStream> {
        val info = StreamInfo.getInfo(mediaId)
        return info.audioStreams.mapNotNull { stream ->
            val url = stream.content.takeIf { stream.isUrl && it.isNotBlank() }
                ?: return@mapNotNull null
            val bitrate = stream.averageBitrate
                .takeIf { it > 0 }
                ?: stream.bitrate.takeIf { it > 0 }

            AudioStream(
                url = url,
                bitrateKbps = bitrate,
                codec = stream.codec,
                mimeType = stream.format?.mimeType
            )
        }
    }
}
