package com.gabby.studiowebwrapper.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.gabby.studiowebwrapper.R
import com.gabby.studiowebwrapper.util.ModelPreferenceManager
import com.gabby.studiowebwrapper.util.ModelVariant

class ModelSelectionDialogFragment : DialogFragment() {

    interface Callbacks {
        fun onModelSelected(variant: ModelVariant)
    }

    private var callbacks: Callbacks? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callbacks = context as? Callbacks
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val currentModel = ModelPreferenceManager.getSelectedModel(context)
        val allModels = ModelPreferenceManager.getAllModels()

        val radioGroup = RadioGroup(context).apply {
            layoutParams = RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.MATCH_PARENT,
                RadioGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = RadioGroup.VERTICAL
        }

        for ((index, model) in allModels.withIndex()) {
            val label = when {
                model.label.contains("Nano") -> "Faster (YOLOv8n)"
                model.label.contains("Small") -> "Accurate (YOLOv8s)"
                else -> model.label
            }
            val radioButton = RadioButton(context).apply {
                id = index
                text = label
                isChecked = model == currentModel
                textSize = 14f
            }
            radioGroup.addView(radioButton)
        }

        return AlertDialog.Builder(context)
            .setTitle("Select YOLOv8 Model")
            .setView(radioGroup)
            .setPositiveButton("Apply") { _, _ ->
                val selectedIndex = radioGroup.checkedRadioButtonId
                if (selectedIndex >= 0 && selectedIndex < allModels.size) {
                    val selectedModel = allModels[selectedIndex]
                    ModelPreferenceManager.setSelectedModel(context, selectedModel)
                    callbacks?.onModelSelected(selectedModel)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
    }
}
