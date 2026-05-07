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
import com.gabby.studiowebwrapper.data.NativeRepository
import com.gabby.studiowebwrapper.util.KaratPreferenceManager
import com.google.android.material.button.MaterialButton
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AccountFragment : Fragment() {

    interface Callbacks {
        fun navigateToWelcome()
        fun navigateToAdminFeedback()
        fun navigateToSettingsPreferences()
    }

    private var callbacks: Callbacks? = null
    private var syncRefreshJob: Job? = null

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
        val totalGradings = 24
        val averageScore = "88.5"
        val preferredKarat = KaratPreferenceManager.getPreferredKarat(requireContext())

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

        // Set statistics
        view.findViewById<TextView>(R.id.totalGradingsValue).text = totalGradings.toString()
        view.findViewById<TextView>(R.id.averageScoreValue).text = averageScore
        view.findViewById<TextView>(R.id.favoriteGemstoneValue).text = preferredKarat

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

    override fun onResume() {
        super.onResume()
        // Refresh preferred karat in case it was changed in settings
        val karat = KaratPreferenceManager.getPreferredKarat(requireContext())
        view?.findViewById<TextView>(R.id.favoriteGemstoneValue)?.text = karat
    }

    override fun onDestroyView() {
        syncRefreshJob?.cancel()
        syncRefreshJob = null
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
}

