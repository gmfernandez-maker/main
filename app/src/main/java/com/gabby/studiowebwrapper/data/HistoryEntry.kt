package com.gabby.studiowebwrapper.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val sourceHash: String = "",
    val resultJson: String,
    val previewUri: String,
    val timestamp: Long
)
