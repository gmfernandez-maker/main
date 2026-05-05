package com.gabby.studiowebwrapper.data

import androidx.lifecycle.LiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReportRepository(private val dao: ReportDao) {
    suspend fun insert(report: Report): Long = withContext(Dispatchers.IO) { dao.insert(report) }
    suspend fun delete(report: Report): Int = withContext(Dispatchers.IO) { dao.delete(report) }
    fun getAll(): LiveData<List<Report>> = dao.getAll()
    fun getById(id: Long): LiveData<Report> = dao.getById(id)
}
