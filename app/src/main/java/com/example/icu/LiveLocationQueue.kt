package com.example.icu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class LiveLocationQueue(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun enqueue(point: LocationSharePoint) {
        val points = load().toMutableList()
        points.add(point)
        save(points.takeLast(MAX_QUEUE_SIZE))
    }

    fun load(): List<LocationSharePoint> {
        val raw = prefs.getString(KEY_POINTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                LocationSharePoint(
                    latitude = item.getDouble("latitude"),
                    longitude = item.getDouble("longitude"),
                    altitude = item.optDoubleOrNull("altitude"),
                    accuracyMeters = item.optDoubleOrNull("accuracyMeters")?.toFloat(),
                    recordedAtMillis = item.getLong("recordedAtMillis")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun clear() {
        prefs.edit().remove(KEY_POINTS).apply()
    }

    private fun save(points: List<LocationSharePoint>) {
        val array = JSONArray()
        points.forEach { point ->
            val item = JSONObject()
                .put("latitude", point.latitude)
                .put("longitude", point.longitude)
                .put("recordedAtMillis", point.recordedAtMillis)
            point.altitude?.let { item.put("altitude", it) }
            point.accuracyMeters?.let { item.put("accuracyMeters", it.toDouble()) }
            array.put(item)
        }
        prefs.edit().putString(KEY_POINTS, array.toString()).apply()
    }

    private fun JSONObject.optDoubleOrNull(name: String): Double? {
        return if (has(name) && !isNull(name)) optDouble(name) else null
    }

    companion object {
        private const val PREFS_NAME = "live_location_queue"
        private const val KEY_POINTS = "points"
        private const val MAX_QUEUE_SIZE = 10_000
    }
}
