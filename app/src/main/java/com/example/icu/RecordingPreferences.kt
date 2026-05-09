package com.example.icu

import android.content.Context

object RecordingPreferences {
    private const val PREFS_NAME = "recording_preferences"
    private const val KEY_HIGH_ACCURACY = "high_accuracy"

    fun isHighAccuracyEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIGH_ACCURACY, false)
    }

    fun setHighAccuracyEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HIGH_ACCURACY, enabled)
            .apply()
    }
}
