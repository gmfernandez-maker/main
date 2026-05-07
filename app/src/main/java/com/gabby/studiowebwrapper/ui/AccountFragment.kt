package com.gabby.studiowebwrapper.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gabby.studiowebwrapper.R
import com.gabby.studiowebwrapper.data.NativeRepository
import com.gabby.studiowebwrapper.util.ModelPreferenceManager
import com.gabby.studiowebwrapper.util.ModelVariant
import com.gabby.studiowebwrapper.util.ThemeModeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AccountFragment : Fragment(), ModelSelectionDialogFragment.Callbacks {

    interface Callbacks {
        fun navigateToWelcome()
        fun navigateToAdminFeedback()
        fun navigateToYoloDemo()
    }

    private var callbacks: Callbacks? = null
    private var syncRefreshJob: Job? = null
    private var modelStatusView: TextView? = null

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
        val preferredKarat = "18K"

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

        val themeSwitch = view.findViewById<SwitchMaterial>(R.id.themeModeSwitch)
        themeSwitch.isChecked = ThemeModeManager.isDarkMode(requireContext())
        themeSwitch.text = if (themeSwitch.isChecked) "Dark" else "Light"
        themeSwitch.setOnCheckedChangeListener { _, isChecked ->
            themeSwitch.text = if (isChecked) "Dark" else "Light"
            ThemeModeManager.setDarkMode(requireContext(), isChecked)
        }

        val useModelSwitch = view.findViewById<SwitchMaterial>(R.id.useModelSwitch)
        useModelSwitch.isChecked = ModelPreferenceManager.useOnDeviceModel(requireContext())
        useModelSwitch.setOnCheckedChangeListener { _, isChecked ->
            ModelPreferenceManager.setUseOnDeviceModel(requireContext(), isChecked)
            Toast.makeText(requireContext(), if (isChecked) "On-device model enabled" else "On-device model disabled", Toast.LENGTH_SHORT).show()
        }

        val useQuantizedSwitch = view.findViewById<SwitchMaterial>(R.id.useQuantizedSwitch)
        useQuantizedSwitch.isChecked = ModelPreferenceManager.useQuantizedModel(requireContext())
        useQuantizedSwitch.setOnCheckedChangeListener { _, isChecked ->
            ModelPreferenceManager.setUseQuantizedModel(requireContext(), isChecked)
            Toast.makeText(requireContext(), if (isChecked) "Quantized model enabled" else "Quantized model disabled", Toast.LENGTH_SHORT).show()
        }

        val feedbackShareSwitch = view.findViewById<SwitchMaterial>(R.id.feedbackShareSwitch)
        feedbackShareSwitch.isChecked = NativeRepository.isFeedbackSharingEnabled(requireContext())
        feedbackShareSwitch.setOnCheckedChangeListener { _, isChecked ->
            NativeRepository.setFeedbackSharingEnabled(requireContext(), isChecked)
            Toast.makeText(requireContext(), if (isChecked) "Feedback sharing enabled (anonymous)" else "Feedback sharing disabled", Toast.LENGTH_SHORT).show()
        }

        // Settings buttons
        view.findViewById<LinearLayout>(R.id.settingsButton).setOnClickListener {
            showModelSelectionDialog()
        }

        view.findViewById<LinearLayout>(R.id.aboutButton).setOnClickListener {
            // TODO: Open about screen
        }

        view.findViewById<LinearLayout>(R.id.yoloDemoButton).setOnClickListener {
            callbacks?.navigateToYoloDemo()
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

    private fun showModelSelectionDialog() {
        ModelSelectionDialogFragment().show(childFragmentManager, "model_selection")
    }

    override fun onModelSelected(variant: ModelVariant) {
        Toast.makeText(
            requireContext(),
            "Model switched to ${variant.label}\nRestart the app to apply changes",
            Toast.LENGTH_LONG
        ).show()
    }
}

