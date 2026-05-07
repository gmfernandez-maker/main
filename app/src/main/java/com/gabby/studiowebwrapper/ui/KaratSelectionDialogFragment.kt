package com.gabby.studiowebwrapper.ui

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.gabby.studiowebwrapper.util.KaratPreferenceManager

class KaratSelectionDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val currentKarat = KaratPreferenceManager.getPreferredKarat(context)
        val karats = KaratPreferenceManager.AVAILABLE_KARATS
        val selectedIndex = karats.indexOf(currentKarat).takeIf { it >= 0 } ?: 2 // Default to 18K

        return AlertDialog.Builder(context)
            .setTitle("Preferred Karat")
            .setSingleChoiceItems(karats.toTypedArray(), selectedIndex) { dialog, which ->
                val selected = karats[which]
                KaratPreferenceManager.setPreferredKarat(context, selected)
                dialog.dismiss()
            }
            .create()
    }
}
