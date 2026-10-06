package com.qabt.eztube.history

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM listening_history ORDER BY playedAt DESC LIMIT 100")
    fun observeRecent(): Flow<List<HistoryEntry>>

    @Upsert
    suspend fun upsert(entry: HistoryEntry)

    @Query("DELETE FROM listening_history WHERE mediaId = :mediaId")
    suspend fun delete(mediaId: String)

    @Query("DELETE FROM listening_history")
    suspend fun clear()
}
