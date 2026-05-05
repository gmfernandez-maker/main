package com.gabby.studiowebwrapper.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(report: Report): Long

    @Query("SELECT * FROM reports ORDER BY timestamp DESC")
    fun getAll(): LiveData<List<Report>>

        @Query("SELECT * FROM reports WHERE id = :reportId")
        fun getById(reportId: Long): LiveData<Report>

    @Delete
    fun delete(report: Report): Int
}
