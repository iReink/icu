package com.example.icu

import android.content.Context
import android.location.Location

class LiveLocationUploader(context: Context) {
    private val sessionStore = SupabaseSessionStore(context.applicationContext)
    private val apiClient = SupabaseApiClient(sessionStore)
    private val queue = LiveLocationQueue(context.applicationContext)

    fun enqueueAndFlush(location: Location) {
        if (!TrackRecordingService.isUsableLocation(location)) return

        val point = LocationSharePoint(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else null,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            recordedAtMillis = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        )
        queue.enqueue(point)
        flush()
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
