package com.example.icu

import org.osmdroid.util.GeoPoint
import java.io.File

enum class TrackType(
    val gpxType: String,
    val title: String,
    val color: Int
) {
    WALK("walk", "Пешком", 0xFF000000.toInt()),
    BIKE("bike", "Велосипед", 0xFF2F5BD1.toInt()),
    CUSTOM("custom", "Ручной", 0xFFDA20AD.toInt());

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
    val timeMillis: Long,
    val startsNewSegment: Boolean = false
) {
    fun toGeoPoint(): GeoPoint = GeoPoint(latitude, longitude)
}

fun List<TrackPoint>.connectedSegments(): List<List<TrackPoint>> {
    if (isEmpty()) return emptyList()
    val segments = mutableListOf<MutableList<TrackPoint>>()
    forEachIndexed { index, point ->
        if (index == 0 || point.startsNewSegment) {
            segments.add(mutableListOf())
        }
        segments.last().add(point)
    }
    return segments
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
