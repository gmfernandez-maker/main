package com.gabby.studiowebwrapper.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface FeedbackDao {
    @Insert
    fun insert(entry: FeedbackEntry): Long

    @Query("SELECT * FROM feedback_entries WHERE userId = :userId ORDER BY timestamp DESC")
    fun getAllForUser(userId: String): List<FeedbackEntry>

    @Query("SELECT * FROM feedback_entries ORDER BY timestamp DESC")
    fun getAllOrdered(): List<FeedbackEntry>

    @Query("SELECT * FROM feedback_entries WHERE previewUri = :previewUri ORDER BY timestamp DESC")
    fun getByPreviewUri(previewUri: String): List<FeedbackEntry>
}
