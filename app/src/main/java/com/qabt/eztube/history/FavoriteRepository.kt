package com.qabt.eztube.history

import com.qabt.eztube.youtube.MediaSummary
import kotlinx.coroutines.flow.Flow

class FavoriteRepository(private val dao: FavoriteDao) {
    val all: Flow<List<FavoriteEntry>> = dao.observeAll()

    suspend fun add(media: MediaSummary) = dao.upsert(
        FavoriteEntry(media.id, media.title, media.channel, media.thumbnailUrl, media.channelUrl, System.currentTimeMillis())
    )

    suspend fun remove(mediaId: String) = dao.delete(mediaId)
}

fun FavoriteEntry.toMediaSummary() = MediaSummary(mediaId, title, channel, thumbnailUrl, channelUrl)
