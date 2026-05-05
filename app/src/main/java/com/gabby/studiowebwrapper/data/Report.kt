package com.gabby.studiowebwrapper.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reports")
data class Report(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long,
    val imagePath: String?,
    val yoloJson: String,
    val topReference: String?,
    val lbpScore: Float,
    val orbScore: Float,
    val finalScore: Float
)
