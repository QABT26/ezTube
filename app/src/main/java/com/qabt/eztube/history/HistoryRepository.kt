package com.qabt.eztube.history

import com.qabt.eztube.youtube.MediaSummary
import kotlinx.coroutines.flow.Flow

class HistoryRepository(private val dao: HistoryDao) {
    val recent: Flow<List<HistoryEntry>> = dao.observeRecent()

    suspend fun record(media: MediaSummary) {
        dao.upsert(
            HistoryEntry(
                mediaId = media.id,
                title = media.title,
                channel = media.channel,
                thumbnailUrl = media.thumbnailUrl,
                playedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun delete(mediaId: String) = dao.delete(mediaId)
    suspend fun clear() = dao.clear()
}

fun HistoryEntry.toMediaSummary() = MediaSummary(
    id = mediaId,
    title = title,
    channel = channel,
    thumbnailUrl = thumbnailUrl
)
