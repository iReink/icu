package com.example.icu

import android.content.Context

enum class MapSourceId {
    OSM,
    GEOAPIFY
}

object MapSourcePreferences {
    private const val PREFS_NAME = "map_source_preferences"
    private const val KEY_MAP_SOURCE = "map_source"

    fun getSelectedSource(context: Context): MapSourceId {
        val rawValue = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MAP_SOURCE, MapSourceId.OSM.name)
        return runCatching { MapSourceId.valueOf(rawValue ?: MapSourceId.OSM.name) }
            .getOrDefault(MapSourceId.OSM)
    }

    fun setSelectedSource(context: Context, source: MapSourceId) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MAP_SOURCE, source.name)
            .apply()
    }
}
