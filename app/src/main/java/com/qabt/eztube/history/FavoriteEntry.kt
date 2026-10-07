package com.qabt.eztube.history

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteEntry(
    @PrimaryKey val mediaId: String,
    val title: String,
    val channel: String,
    val thumbnailUrl: String?,
    val channelUrl: String?,
    val addedAt: Long,
    val updatedAt: Long = addedAt,
    val syncState: String = "LOCAL"
)
