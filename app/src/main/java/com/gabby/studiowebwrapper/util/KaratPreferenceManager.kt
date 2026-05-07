package com.gabby.studiowebwrapper.util

import android.content.Context

object KaratPreferenceManager {
    private const val PREF_NAME = "karat_preference"
    private const val KEY_PREFERRED_KARAT = "preferred_karat"

    val AVAILABLE_KARATS = listOf("24K", "22K", "18K", "14K", "10K", "999", "916", "750", "585")

    fun getPreferredKarat(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PREFERRED_KARAT, "18K") ?: "18K"
    }

    fun setPreferredKarat(context: Context, karat: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PREFERRED_KARAT, karat).apply()
    }
}
