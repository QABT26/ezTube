package com.qabt.eztube.history

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.qabt.eztube.youtube.MediaSummary

@Entity(tableName = "watch_later")
data class WatchLaterEntry(
    @PrimaryKey val mediaId: String,
    val title: String,
    val channel: String,
    val thumbnailUrl: String?,
    val channelUrl: String?,
    val addedAt: Long,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = addedAt,
    @ColumnInfo(defaultValue = "'LOCAL'") val syncState: String = "LOCAL"
)

fun WatchLaterEntry.toMediaSummary() =
    MediaSummary(mediaId, title, channel, thumbnailUrl, channelUrl)
