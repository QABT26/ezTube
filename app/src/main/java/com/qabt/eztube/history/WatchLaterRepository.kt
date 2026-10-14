package com.qabt.eztube.history

import com.qabt.eztube.youtube.MediaSummary
import kotlinx.coroutines.flow.Flow

class WatchLaterRepository(private val dao: WatchLaterDao) {
    val all: Flow<List<WatchLaterEntry>> = dao.observeAll()

    suspend fun add(media: MediaSummary) {
        val now = System.currentTimeMillis()
        dao.upsert(
            WatchLaterEntry(
                mediaId = media.id,
                title = media.title,
                channel = media.channel,
                thumbnailUrl = media.thumbnailUrl,
                channelUrl = media.channelUrl,
                addedAt = now,
                updatedAt = now,
                syncState = "LOCAL"
            )
        )
    }

    suspend fun remove(mediaId: String) = dao.delete(mediaId)

    suspend fun removeMany(mediaIds: Collection<String>) {
        mediaIds.distinct().forEach { dao.delete(it) }
    }

    suspend fun clear() = dao.clear()
}
