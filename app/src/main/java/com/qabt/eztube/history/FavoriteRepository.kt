package com.qabt.eztube.history

import com.qabt.eztube.youtube.MediaSummary
import kotlinx.coroutines.flow.Flow

class FavoriteRepository(private val dao: FavoriteDao) {
    val all: Flow<List<FavoriteEntry>> = dao.observeAll()

    suspend fun add(media: MediaSummary) {
        val now = System.currentTimeMillis()
        dao.upsert(
            FavoriteEntry(
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
}

fun FavoriteEntry.toMediaSummary() = MediaSummary(mediaId, title, channel, thumbnailUrl, channelUrl)
