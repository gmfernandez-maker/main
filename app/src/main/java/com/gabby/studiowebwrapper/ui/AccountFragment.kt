package com.gabby.studiowebwrapper.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gabby.studiowebwrapper.R
import com.gabby.studiowebwrapper.data.AppDatabase
import com.gabby.studiowebwrapper.data.HistoryEntry
import com.gabby.studiowebwrapper.data.NativeRepository
import com.gabby.studiowebwrapper.model.SuggestMetadataOutput
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class AccountFragment : Fragment() {

    interface Callbacks {
        fun navigateToWelcome()
        fun navigateToAdminFeedback()
        fun navigateToSettingsPreferences()
    }

    private var callbacks: Callbacks? = null
    private var syncRefreshJob: Job? = null
    private var statsJob: Job? = null
    private val gson = Gson()

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callbacks = context as? Callbacks
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_account, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentUser = NativeRepository.getCurrentUser(requireContext())
        val userName = currentUser?.fullName?.takeIf { it.isNotBlank() } ?: "User"
        val userEmail = currentUser?.email ?: "No email"

        // Set user info
        view.findViewById<TextView>(R.id.userNameText).text = userName
        view.findViewById<TextView>(R.id.userEmailText).text = userEmail
        val lastSyncText = view.findViewById<TextView>(R.id.lastSyncText)
        updateLastSyncLabel(lastSyncText)

        syncRefreshJob?.cancel()
        syncRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    updateLastSyncLabel(lastSyncText)
                    delay(5000)
                }
            }
        }

        bindStatistics(view, currentUser?.id.orEmpty())

        // Settings button
        view.findViewById<LinearLayout>(R.id.settingsButton).setOnClickListener {
            callbacks?.navigateToSettingsPreferences()
        }

        view.findViewById<LinearLayout>(R.id.feedbackAdminButton).setOnClickListener {
            callbacks?.navigateToAdminFeedback()
        }

        // Logout button
        view.findViewById<MaterialButton>(R.id.logoutButton).setOnClickListener {
            NativeRepository.logout(requireContext())
            callbacks?.navigateToWelcome()
        }
    }

    override fun onDestroyView() {
        syncRefreshJob?.cancel()
        syncRefreshJob = null
        statsJob?.cancel()
        statsJob = null
        super.onDestroyView()
    }

    private fun updateLastSyncLabel(view: TextView) {
        val lastSyncMillis = NativeRepository.getLastHistorySyncAt(requireContext())
        view.text = if (lastSyncMillis > 0L) {
            val formatted = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(lastSyncMillis))
            "Last sync: $formatted"
        } else {
            "Last sync: Not synced yet"
        }
    }

    private fun bindStatistics(view: View, userId: String) {
        val totalGradingsView = view.findViewById<TextView>(R.id.totalGradingsValue)
        val averageScoreView = view.findViewById<TextView>(R.id.averageScoreValue)
        val topKaratView = view.findViewById<TextView>(R.id.favoriteGemstoneValue)

        statsJob?.cancel()
        if (userId.isBlank()) {
            updateStatisticsViews(totalGradingsView, averageScoreView, topKaratView, AccountStats.empty())
            return
        }

        updateStatisticsViews(totalGradingsView, averageScoreView, topKaratView, AccountStats.empty())
        val appContext = requireContext().applicationContext
        statsJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppDatabase.getInstance(appContext).historyDao().getAllForUser(userId).collect { entries ->
                    updateStatisticsViews(
                        totalGradingsView,
                        averageScoreView,
                        topKaratView,
                        buildAccountStats(entries)
                    )
                }
            }
        }
    }

    private fun updateStatisticsViews(
        totalGradingsView: TextView,
        averageScoreView: TextView,
        topKaratView: TextView,
        stats: AccountStats
    ) {
        totalGradingsView.text = stats.totalGradings.toString()
        averageScoreView.text = stats.averageScoreLabel
        topKaratView.text = stats.topKaratLabel
    }

    private fun buildAccountStats(entries: List<HistoryEntry>): AccountStats {
        val parsedResults = entries.mapNotNull { entry ->
            runCatching { gson.fromJson(entry.resultJson, SuggestMetadataOutput::class.java) }.getOrNull()
        }
        val scores = parsedResults
            .map { computedTotalScore(it) }
            .filter { it > 0 }
        val averageScore = scores.takeIf { it.isNotEmpty() }?.average()
        val topKarat = parsedResults
            .mapNotNull { it.purity?.trim() }
            .filter { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
            .groupingBy { it }
            .eachCount()
            .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            ?.key

        return AccountStats(
            totalGradings = entries.size,
            averageScoreLabel = averageScore?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "--",
            topKaratLabel = topKarat ?: "N/A"
        )
    }

    private fun computedTotalScore(result: SuggestMetadataOutput): Int {
        val persistedTotal = result.totalComputedScore.coerceIn(0, 100)
        if (persistedTotal > 0) return persistedTotal

        val yolo = result.yoloScore.coerceIn(0, 100)
        val lbp = result.lbpScore.coerceIn(0, 100)
        val orb = result.orbScore.coerceIn(0, 100)
        val hasComponents = yolo > 0 || lbp > 0 || orb > 0
        return if (hasComponents) (yolo + lbp + orb) / 3 else result.qualityScore.coerceIn(0, 100)
    }

    private data class AccountStats(
        val totalGradings: Int,
        val averageScoreLabel: String,
        val topKaratLabel: String
    ) {
        companion object {
            fun empty(): AccountStats = AccountStats(
                totalGradings = 0,
                averageScoreLabel = "--",
                topKaratLabel = "N/A"
            )
        }
    }
}

