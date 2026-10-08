package com.qabt.eztube.history

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "listening_history")
data class HistoryEntry(
    @PrimaryKey val mediaId: String,
    val title: String,
    val channel: String,
    val thumbnailUrl: String?,
    val channelUrl: String?,
    val playedAt: Long,
    @ColumnInfo(defaultValue = "0") val positionMs: Long = 0L,
    @ColumnInfo(defaultValue = "0") val durationMs: Long = 0L,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = playedAt,
    @ColumnInfo(defaultValue = "'LOCAL'") val syncState: String = "LOCAL"
)
 
val HistoryEntry.progress: Float
    get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
