package com.gabby.studiowebwrapper.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Delete
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history WHERE userId = :userId ORDER BY timestamp DESC")
    fun getAllForUser(userId: String): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history WHERE userId = :userId ORDER BY timestamp DESC")
    fun getAllForUserOnce(userId: String): List<HistoryEntry>

    @Query("SELECT COUNT(*) FROM history WHERE userId = :userId AND timestamp = :timestamp AND previewUri = :previewUri")
    fun countByUserAndTimestampAndPreview(userId: String, timestamp: Long, previewUri: String): Int

    @Query("SELECT COUNT(*) FROM history WHERE userId = :userId AND sourceHash = :sourceHash")
    fun countByUserAndSourceHash(userId: String, sourceHash: String): Int

    @Query("SELECT * FROM history WHERE userId = :userId AND sourceHash = :sourceHash ORDER BY timestamp DESC LIMIT 1")
    fun findLatestByUserAndSourceHash(userId: String, sourceHash: String): HistoryEntry?

    @Query("SELECT * FROM history WHERE userId = :userId AND previewUri = :previewUri ORDER BY timestamp DESC LIMIT 1")
    fun findLatestByUserAndPreviewUri(userId: String, previewUri: String): HistoryEntry?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    fun insert(entry: HistoryEntry): Long

    @Delete
    fun delete(entry: HistoryEntry): Int
}
