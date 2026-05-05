package com.gabby.studiowebwrapper.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class ReportViewModel(application: Application) : AndroidViewModel(application) {
    private val repo: ReportRepository

    init {
        val dao = AppDatabase.getInstance(application).reportDao()
        repo = ReportRepository(dao)
    }

    fun insert(report: Report, onComplete: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = repo.insert(report)
            onComplete(id)
        }
    }

    fun delete(report: Report, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            repo.delete(report)
            onComplete()
        }
    }

    fun getAll(): LiveData<List<Report>> = repo.getAll()
    fun getById(id: Long): LiveData<Report> = repo.getById(id)
}
