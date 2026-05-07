package com.gabby.studiowebwrapper.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "feedback_entries")
data class FeedbackEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val resultJson: String,
    val previewUri: String,
    val selection: String,
    val comment: String?,
    val modelConfidence: Int,
    val routedTo: String?,
    val timestamp: Long = System.currentTimeMillis()
)
