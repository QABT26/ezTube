package com.qabt.eztube.history

import androidx.room.ColumnInfo
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
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = addedAt,
    @ColumnInfo(defaultValue = "'LOCAL'") val syncState: String = "LOCAL"
)
