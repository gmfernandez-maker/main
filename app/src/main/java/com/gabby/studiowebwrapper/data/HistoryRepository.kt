package com.gabby.studiowebwrapper.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class HistoryRepository(private val dao: HistoryDao) {
    fun getAllForUser(userId: String): Flow<List<HistoryEntry>> = dao.getAllForUser(userId)

    suspend fun insert(entry: HistoryEntry): Long = withContext(Dispatchers.IO) { dao.insert(entry) }

    suspend fun delete(entry: HistoryEntry): Int = withContext(Dispatchers.IO) { dao.delete(entry) }
}
