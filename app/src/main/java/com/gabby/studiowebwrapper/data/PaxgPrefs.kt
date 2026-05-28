package com.gabby.studiowebwrapper.data

import android.content.Context

object PaxgPrefs {
    private const val PREFS = "paxg_prefs"
    private const val KEY_PRICE_PHP = "pref_paxg_price_php"
    private const val KEY_LAST_UPDATED = "pref_paxg_last_updated_ms"
    private const val KEY_API_KEY = "pref_paxg_api_key"

    fun savePricePhp(context: Context, pricePhp: Double, epochMs: Long) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit().putLong(KEY_LAST_UPDATED, epochMs).putString(KEY_PRICE_PHP, pricePhp.toString()).apply()
    }

    fun getCachedPricePhp(context: Context): Double? {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = sp.getString(KEY_PRICE_PHP, null) ?: return null
        return s.toDoubleOrNull()
    }

    fun getLastUpdatedMs(context: Context): Long {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getLong(KEY_LAST_UPDATED, 0L)
    }

    fun saveApiKey(context: Context, apiKey: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_API_KEY, apiKey).apply()
    }

    fun getApiKey(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API_KEY, null)
    }
}
