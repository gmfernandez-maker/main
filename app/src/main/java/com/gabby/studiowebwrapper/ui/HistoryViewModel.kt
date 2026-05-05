package com.gabby.studiowebwrapper.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.gabby.studiowebwrapper.data.AppDatabase
import com.gabby.studiowebwrapper.data.HistoryEntry
import com.gabby.studiowebwrapper.data.HistoryRepository
import com.gabby.studiowebwrapper.data.NativeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = HistoryRepository(AppDatabase.getInstance(application).historyDao())
    private val currentUserId: String = NativeRepository.getCurrentUser(application)?.id.orEmpty()
    val history: LiveData<List<HistoryEntry>> = repo.getAllForUser(currentUserId).asLiveData()

    fun delete(entry: HistoryEntry) {
        viewModelScope.launch(Dispatchers.IO) { repo.delete(entry) }
    }

    fun insert(entry: HistoryEntry) {
        val userBoundEntry = entry.copy(userId = currentUserId)
        viewModelScope.launch(Dispatchers.IO) { repo.insert(userBoundEntry) }
    }
}
