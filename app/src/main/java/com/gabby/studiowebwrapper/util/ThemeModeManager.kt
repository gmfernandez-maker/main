package com.gabby.studiowebwrapper.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemeModeManager {
    private const val PREFS = "ui_preferences"
    private const val KEY_DARK_MODE = "dark_mode"

    fun applySavedTheme(context: Context) {
        val isDarkMode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK_MODE, true)
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    fun isDarkMode(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK_MODE, true)
    }

    fun setDarkMode(context: Context, isDarkMode: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DARK_MODE, isDarkMode)
            .apply()
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}
