package com.qabt.eztube.history

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchLaterDao {
    @Query("SELECT * FROM watch_later ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<WatchLaterEntry>>

    @Upsert
    suspend fun upsert(entry: WatchLaterEntry)

    @Query("DELETE FROM watch_later WHERE mediaId = :mediaId")
    suspend fun delete(mediaId: String)

    @Query("DELETE FROM watch_later")
    suspend fun clear()
}
