package com.example.icu

import android.content.Context
import android.location.Location

class LiveLocationUploader(context: Context) {
    private val sessionStore = SupabaseSessionStore(context.applicationContext)
    private val apiClient = SupabaseApiClient(sessionStore)
    private val queue = LiveLocationQueue(context.applicationContext)

    fun enqueueAndFlush(location: Location) {
        if (!TrackRecordingService.isUsableLocation(location)) return

        enqueueAndFlush(location.toSharePoint())
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
        val session = runCatching { apiClient.activeSession() }.getOrNull() ?: return
        val points = queue.load()
        if (points.isEmpty()) return
        runCatching {
            apiClient.uploadLocationPoints(session, points)
        }.onSuccess {
            queue.clear()
        }
    }
}
