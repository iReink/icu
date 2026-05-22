package com.example.icu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.util.UUID

data class SavedPoint(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val createdAtMillis: Long
) {
    fun toGeoPoint(): GeoPoint = GeoPoint(latitude, longitude)
}

class SavedPointStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadPoints(): List<SavedPoint> {
        val raw = prefs.getString(KEY_POINTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                SavedPoint(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    latitude = item.getDouble("latitude"),
                    longitude = item.getDouble("longitude"),
                    createdAtMillis = item.getLong("createdAtMillis")
                )
            }.sortedByDescending { it.createdAtMillis }
        }.getOrDefault(emptyList())
    }

    fun savePoint(name: String, point: GeoPoint): SavedPoint {
        val now = System.currentTimeMillis()
        val savedPoint = SavedPoint(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { defaultPointName(now) },
            latitude = point.latitude,
            longitude = point.longitude,
            createdAtMillis = now
        )
        save(loadPoints() + savedPoint)
        return savedPoint
    }

    private fun save(points: List<SavedPoint>) {
        val array = JSONArray()
        points.sortedByDescending { it.createdAtMillis }.forEach { point ->
            array.put(JSONObject()
                .put("id", point.id)
                .put("name", point.name)
                .put("latitude", point.latitude)
                .put("longitude", point.longitude)
                .put("createdAtMillis", point.createdAtMillis)
            )
        }
        prefs.edit().putString(KEY_POINTS, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "saved_points"
        private const val KEY_POINTS = "points"

        fun defaultPointName(timeMillis: Long): String {
            return "Точка ${GpxTrackStore.defaultTrackName(TrackType.CUSTOM, timeMillis).substringAfter(' ')}"
        }
    }
}
