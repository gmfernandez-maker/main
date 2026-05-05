package com.gabby.studiowebwrapper.util

import android.content.Context

enum class ModelVariant(val filename: String, val label: String, val description: String) {
    NANO("yolov8n_ts.pt", "YOLOv8n (Nano)", "Fast and lightweight, lower accuracy"),
    SMALL("yolov8s_ts.pt", "YOLOv8s (Small)", "Balanced speed and accuracy")
}

object ModelPreferenceManager {
    private const val PREFS = "model_preferences"
    private const val KEY_MODEL_VARIANT = "model_variant"
    private const val DEFAULT_MODEL = "NANO"
    private const val KEY_USE_ON_DEVICE = "use_on_device_model"
    private const val KEY_USE_QUANTIZED = "use_quantized_model"
    private const val KEY_NUM_THREADS = "model_num_threads"

    fun getSelectedModel(context: Context): ModelVariant {
        val modelName = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODEL_VARIANT, DEFAULT_MODEL)
            ?: DEFAULT_MODEL
        return try {
            ModelVariant.valueOf(modelName)
        } catch (e: IllegalArgumentException) {
            ModelVariant.NANO
        }
    }

    fun setSelectedModel(context: Context, variant: ModelVariant) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODEL_VARIANT, variant.name)
            .apply()
    }

    fun setUseOnDeviceModel(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_USE_ON_DEVICE, enabled).apply()
    }

    fun useOnDeviceModel(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_USE_ON_DEVICE, true)
    }

    fun setUseQuantizedModel(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_USE_QUANTIZED, enabled).apply()
    }

    fun useQuantizedModel(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_USE_QUANTIZED, false)
    }

    fun setNumThreads(context: Context, threads: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_NUM_THREADS, threads).apply()
    }

    fun getNumThreads(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val available = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return prefs.getInt(KEY_NUM_THREADS, available.coerceAtMost(4))
    }

    fun getModelFilename(context: Context): String {
        return getSelectedModel(context).filename
    }

    fun getAllModels(): List<ModelVariant> {
        return ModelVariant.values().toList()
    }
}
