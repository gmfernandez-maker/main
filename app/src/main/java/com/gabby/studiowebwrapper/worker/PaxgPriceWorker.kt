package com.gabby.studiowebwrapper.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gabby.studiowebwrapper.data.PaxgPriceRepository

class PaxgPriceWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val fetched = PaxgPriceRepository.fetchAndCachePricePhp(applicationContext)
        return if (fetched != null) Result.success() else Result.retry()
    }
}
