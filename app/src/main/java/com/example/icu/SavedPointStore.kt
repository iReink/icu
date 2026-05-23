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
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val sortOrder: Long,
    val visible: Boolean
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
                    createdAtMillis = item.getLong("createdAtMillis"),
                    updatedAtMillis = item.optLong("updatedAtMillis", item.getLong("createdAtMillis")),
                    sortOrder = item.optLong("sortOrder", -item.getLong("createdAtMillis")),
                    visible = item.optBoolean("visible", true)
                )
            }.sortedWith(compareBy<SavedPoint> { it.sortOrder }.thenByDescending { it.createdAtMillis })
        }.getOrDefault(emptyList())
    }

    fun savePoint(name: String, point: GeoPoint): SavedPoint {
        val now = System.currentTimeMillis()
        val savedPoint = SavedPoint(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { defaultPointName(now) },
            latitude = point.latitude,
            longitude = point.longitude,
            createdAtMillis = now,
            updatedAtMillis = now,
            sortOrder = (loadPoints().minOfOrNull { it.sortOrder } ?: 0L) - 1L,
            visible = true
        )
        save(loadPoints() + savedPoint)
        return savedPoint
    }

    fun renamePoint(point: SavedPoint, name: String): SavedPoint {
        val updated = point.copy(
            name = name.ifBlank { point.name },
            updatedAtMillis = System.currentTimeMillis()
        )
        save(loadPoints().map { if (it.id == point.id) updated else it })
        return updated
    }

    fun setPointVisibility(point: SavedPoint, visible: Boolean): SavedPoint {
        val updated = point.copy(
            visible = visible,
            updatedAtMillis = System.currentTimeMillis()
        )
        save(loadPoints().map { if (it.id == point.id) updated else it })
        return updated
    }

    fun deletePoint(point: SavedPoint) {
        save(loadPoints().filterNot { it.id == point.id })
        prefs.edit().putBoolean(deletedKey(point.id), true).apply()
    }

    fun reorderPoints(orderedPoints: List<SavedPoint>) {
        if (orderedPoints.isEmpty()) return
        val now = System.currentTimeMillis()
        val updatedById = orderedPoints.mapIndexed { index, point ->
            point.id to point.copy(sortOrder = index.toLong(), updatedAtMillis = now)
        }.toMap()
        save(loadPoints().map { updatedById[it.id] ?: it })
    }

    fun upsertSyncedPoints(points: List<SavedPoint>) {
        if (points.isEmpty()) return
        val merged = loadPoints().associateBy { it.id }.toMutableMap()
        points.forEach { point ->
            val local = merged[point.id]
            if (local == null || point.updatedAtMillis >= local.updatedAtMillis) {
                merged[point.id] = point
            }
        }
        save(merged.values.toList())
    }

    fun deletedPointIds(): List<String> {
        return prefs.all.keys
            .filter { it.startsWith(DELETED_PREFIX) && prefs.getBoolean(it, false) }
            .map { it.removePrefix(DELETED_PREFIX) }
    }

    fun clearDeleted(pointId: String) {
        prefs.edit().remove(deletedKey(pointId)).apply()
    }

    fun save(points: List<SavedPoint>) {
        val array = JSONArray()
        points.sortedWith(compareBy<SavedPoint> { it.sortOrder }.thenByDescending { it.createdAtMillis }).forEach { point ->
            array.put(JSONObject()
                .put("id", point.id)
                .put("name", point.name)
                .put("latitude", point.latitude)
                .put("longitude", point.longitude)
                .put("createdAtMillis", point.createdAtMillis)
                .put("updatedAtMillis", point.updatedAtMillis)
                .put("sortOrder", point.sortOrder)
                .put("visible", point.visible)
            )
        }
        prefs.edit().putString(KEY_POINTS, array.toString()).apply()
    }

    private fun deletedKey(pointId: String) = "$DELETED_PREFIX$pointId"

    companion object {
        private const val PREFS_NAME = "saved_points"
        private const val KEY_POINTS = "points"
        private const val DELETED_PREFIX = "deleted."

        fun defaultPointName(timeMillis: Long): String {
            return "Точка ${GpxTrackStore.defaultTrackName(TrackType.CUSTOM, timeMillis).substringAfter(' ')}"
        }
    }
}
