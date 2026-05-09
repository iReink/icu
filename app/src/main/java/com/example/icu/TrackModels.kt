package com.example.icu

import android.graphics.Color
import org.osmdroid.util.GeoPoint
import java.io.File

enum class TrackType(
    val gpxType: String,
    val title: String,
    val color: Int
) {
    WALK("walk", "Пешком", Color.BLACK),
    BIKE("bike", "Велосипед", Color.rgb(47, 91, 209));

    companion object {
        fun fromGpxType(value: String?): TrackType {
            return entries.firstOrNull { it.gpxType == value } ?: WALK
        }
    }
}

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val timeMillis: Long
) {
    fun toGeoPoint(): GeoPoint = GeoPoint(latitude, longitude)
}

data class RecordedTrack(
    val name: String,
    val type: TrackType,
    val points: List<TrackPoint>,
    val distanceMeters: Float,
    val durationMillis: Long,
    val startedAtMillis: Long,
    val visible: Boolean,
    val file: File
)

data class RecordingState(
    val isRecording: Boolean = false,
    val type: TrackType? = null,
    val startedAtMillis: Long = 0L,
    val distanceMeters: Float = 0f,
    val points: List<TrackPoint> = emptyList()
)
