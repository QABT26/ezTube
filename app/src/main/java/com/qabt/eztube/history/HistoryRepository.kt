package com.qabt.eztube.history

import com.qabt.eztube.youtube.MediaSummary
import kotlinx.coroutines.flow.Flow

class HistoryRepository(private val dao: HistoryDao) {
    val recent: Flow<List<HistoryEntry>> = dao.observeRecent()

    suspend fun record(media: MediaSummary, positionMs: Long = 0L, durationMs: Long = 0L) {
        val now = System.currentTimeMillis()
        dao.upsert(
            HistoryEntry(
                mediaId = media.id,
                title = media.title,
                channel = media.channel,
                thumbnailUrl = media.thumbnailUrl,
                channelUrl = media.channelUrl,
                playedAt = now,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                updatedAt = now,
                syncState = "LOCAL"
            )
        )
    }

    suspend fun updateProgress(media: MediaSummary, positionMs: Long, durationMs: Long) {
        val now = System.currentTimeMillis()
        dao.upsert(
            HistoryEntry(
                mediaId = media.id,
                title = media.title,
                channel = media.channel,
                thumbnailUrl = media.thumbnailUrl,
                channelUrl = media.channelUrl,
                playedAt = now,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                updatedAt = now,
                syncState = "LOCAL"
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
    thumbnailUrl = thumbnailUrl,
    channelUrl = channelUrl
)
