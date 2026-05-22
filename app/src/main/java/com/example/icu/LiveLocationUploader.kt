package com.example.icu

import android.content.Context
import android.location.Location

class LiveLocationUploader(context: Context) {
    private val appContext = context.applicationContext
    private val sessionStore = SupabaseSessionStore(context.applicationContext)
    private val apiClient = SupabaseApiClient(sessionStore)
    private val queue = LiveLocationQueue(context.applicationContext)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun enqueueAndFlush(location: Location) {
        val point = pointToUpload(location) ?: return

        enqueueAndFlush(point)
    }

    fun enqueueAndFlush(point: LocationSharePoint) {
        queue.enqueue(point)
        flush()
    }

    private fun Location.toSharePoint(): LocationSharePoint {
        return LocationSharePoint(
            latitude = latitude,
            longitude = longitude,
            altitude = if (hasAltitude()) altitude else null,
            accuracyMeters = if (hasAccuracy()) accuracy else null,
            recordedAtMillis = time.takeIf { it > 0L } ?: System.currentTimeMillis()
        )
    }

    fun flush() {
        var session = runCatching { apiClient.activeSession() }.getOrNull() ?: return
        val points = queue.load()
        if (points.isEmpty()) return
        runCatching {
            try {
                apiClient.uploadLocationPoints(session, points)
            } catch (error: SupabaseException) {
                if (error.statusCode != 401) throw error
                session = apiClient.activeSession(forceRefresh = true)
                apiClient.uploadLocationPoints(session, points)
            }
        }.onSuccess {
            queue.clear()
        }
    }

    private fun pointToUpload(location: Location): LocationSharePoint? {
        val now = System.currentTimeMillis()
        val rawPoint = location.toSharePoint()
        val lastGoodPoint = loadLastGoodPoint()
        val candidate = if (
            TrackRecordingService.isUsableLocation(location) ||
            isLargeMoveDespitePoorAccuracy(rawPoint, lastGoodPoint)
        ) {
            rawPoint.copy(recordedAtMillis = now)
        } else {
            null
        }

        return when {
            candidate == null -> lastGoodPoint?.copy(recordedAtMillis = now)
            lastGoodPoint == null -> candidate.also { saveLastGoodPoint(it) }
            distanceMeters(lastGoodPoint, candidate) >= LIVE_LOCATION_NOISE_THRESHOLD_METERS -> {
                candidate.also { saveLastGoodPoint(it) }
            }
            else -> lastGoodPoint.copy(recordedAtMillis = now)
        }
    }

    private fun isLargeMoveDespitePoorAccuracy(
        point: LocationSharePoint,
        previous: LocationSharePoint?
    ): Boolean {
        val accuracy = point.accuracyMeters ?: return false
        if (accuracy <= TrackRecordingService.MAX_ACCEPTED_ACCURACY_METERS) return true
        if (previous == null) return false
        return distanceMeters(previous, point) > accuracy * INACCURATE_LOCATION_DISTANCE_MULTIPLIER
    }

    private fun distanceMeters(first: LocationSharePoint, second: LocationSharePoint): Float {
        val result = FloatArray(1)
        Location.distanceBetween(
            first.latitude,
            first.longitude,
            second.latitude,
            second.longitude,
            result
        )
        return result[0]
    }

    private fun loadLastGoodPoint(): LocationSharePoint? {
        if (!prefs.contains(KEY_LAST_LATITUDE) || !prefs.contains(KEY_LAST_LONGITUDE)) return null
        val altitude = prefs.getFloat(KEY_LAST_ALTITUDE, NO_FLOAT).takeIf { !it.isNaN() }?.toDouble()
        val accuracy = prefs.getFloat(KEY_LAST_ACCURACY, NO_FLOAT).takeIf { !it.isNaN() }
        return LocationSharePoint(
            latitude = Double.fromBits(prefs.getLong(KEY_LAST_LATITUDE, NO_DOUBLE.toBits())),
            longitude = Double.fromBits(prefs.getLong(KEY_LAST_LONGITUDE, NO_DOUBLE.toBits())),
            altitude = altitude,
            accuracyMeters = accuracy,
            recordedAtMillis = prefs.getLong(KEY_LAST_RECORDED_AT, 0L)
        )
    }

    private fun saveLastGoodPoint(point: LocationSharePoint) {
        prefs.edit()
            .putLong(KEY_LAST_LATITUDE, point.latitude.toBits())
            .putLong(KEY_LAST_LONGITUDE, point.longitude.toBits())
            .putFloat(KEY_LAST_ALTITUDE, point.altitude?.toFloat() ?: NO_FLOAT)
            .putFloat(KEY_LAST_ACCURACY, point.accuracyMeters ?: NO_FLOAT)
            .putLong(KEY_LAST_RECORDED_AT, point.recordedAtMillis)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "live_location_state"
        private const val LIVE_LOCATION_NOISE_THRESHOLD_METERS = 5f
        private const val INACCURATE_LOCATION_DISTANCE_MULTIPLIER = 3f
        private const val KEY_LAST_LATITUDE = "last_latitude"
        private const val KEY_LAST_LONGITUDE = "last_longitude"
        private const val KEY_LAST_ALTITUDE = "last_altitude"
        private const val KEY_LAST_ACCURACY = "last_accuracy"
        private const val KEY_LAST_RECORDED_AT = "last_recorded_at"
        private const val NO_DOUBLE = Double.NaN
        private const val NO_FLOAT = Float.NaN
    }
}
