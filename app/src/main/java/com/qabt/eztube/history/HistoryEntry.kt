package com.qabt.eztube.history

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "listening_history")
data class HistoryEntry(
    @PrimaryKey val mediaId: String,
    val title: String,
    val channel: String,
    val thumbnailUrl: String?,
    val channelUrl: String?,
    val playedAt: Long
)
