package com.gabby.studiowebwrapper.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.gabby.studiowebwrapper.R
import com.gabby.studiowebwrapper.data.NativeRepository
import com.gabby.studiowebwrapper.util.KaratPreferenceManager
import com.gabby.studiowebwrapper.util.ModelPreferenceManager
import com.gabby.studiowebwrapper.util.ModelVariant
import com.gabby.studiowebwrapper.util.ThemeModeManager
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsPreferencesFragment : Fragment(), ModelSelectionDialogFragment.Callbacks {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_settings_preferences, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Back button
        view.findViewById<LinearLayout>(R.id.backButton).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // Theme Mode Switch
        val themeSwitch = view.findViewById<SwitchMaterial>(R.id.themeModeSwitch)
        themeSwitch.isChecked = ThemeModeManager.isDarkMode(requireContext())
        themeSwitch.text = if (themeSwitch.isChecked) "Dark" else "Light"
        themeSwitch.setOnCheckedChangeListener { _, isChecked ->
            themeSwitch.text = if (isChecked) "Dark" else "Light"
            ThemeModeManager.setDarkMode(requireContext(), isChecked)
        }

        // Use On-Device Model Switch
        val useModelSwitch = view.findViewById<SwitchMaterial>(R.id.useModelSwitch)
        useModelSwitch.isChecked = ModelPreferenceManager.useOnDeviceModel(requireContext())
        useModelSwitch.setOnCheckedChangeListener { _, isChecked ->
            ModelPreferenceManager.setUseOnDeviceModel(requireContext(), isChecked)
            Toast.makeText(requireContext(), if (isChecked) "On-device model enabled" else "On-device model disabled", Toast.LENGTH_SHORT).show()
        }

        // Use Quantized Model Switch
        val useQuantizedSwitch = view.findViewById<SwitchMaterial>(R.id.useQuantizedSwitch)
        useQuantizedSwitch.isChecked = ModelPreferenceManager.useQuantizedModel(requireContext())
        useQuantizedSwitch.setOnCheckedChangeListener { _, isChecked ->
            ModelPreferenceManager.setUseQuantizedModel(requireContext(), isChecked)
            Toast.makeText(requireContext(), if (isChecked) "Quantized model enabled" else "Quantized model disabled", Toast.LENGTH_SHORT).show()
        }

        // Feedback Sharing Switch
        val feedbackShareSwitch = view.findViewById<SwitchMaterial>(R.id.feedbackShareSwitch)
        feedbackShareSwitch.isChecked = NativeRepository.isFeedbackSharingEnabled(requireContext())
        feedbackShareSwitch.setOnCheckedChangeListener { _, isChecked ->
            NativeRepository.setFeedbackSharingEnabled(requireContext(), isChecked)
            Toast.makeText(requireContext(), if (isChecked) "Feedback sharing enabled (anonymous)" else "Feedback sharing disabled", Toast.LENGTH_SHORT).show()
        }

        // Model Selection Button
        view.findViewById<LinearLayout>(R.id.modelSelectionButton).setOnClickListener {
            showModelSelectionDialog()
        }
        updateModelButtonText(view)

        // Karat Preference Button
        view.findViewById<LinearLayout>(R.id.karatPreferenceButton).setOnClickListener {
            showKaratSelectionDialog()
        }
        updateKaratButtonText(view)
    }

    private fun updateModelButtonText(view: View) {
        val currentModel = ModelPreferenceManager.getSelectedModel(requireContext())
        val modelText = view.findViewById<TextView>(R.id.modelSelectionValue)
        modelText.text = currentModel.label
    }

    private fun updateKaratButtonText(view: View) {
        val karat = KaratPreferenceManager.getPreferredKarat(requireContext())
        val karatText = view.findViewById<TextView>(R.id.karatPreferenceValue)
        karatText.text = karat
    }

    private fun showModelSelectionDialog() {
        ModelSelectionDialogFragment().show(childFragmentManager, "model_selection")
    }

    private fun showKaratSelectionDialog() {
        KaratSelectionDialogFragment().show(childFragmentManager, "karat_selection")
    }

    override fun onModelSelected(variant: ModelVariant) {
        updateModelButtonText(view!!)
        Toast.makeText(
            requireContext(),
            "Model switched to ${variant.label}\nRestart the app to apply changes",
            Toast.LENGTH_LONG
        ).show()
    }
}
