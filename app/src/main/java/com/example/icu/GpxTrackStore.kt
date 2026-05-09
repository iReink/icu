package com.example.icu

import android.content.Context
import android.location.Location
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class GpxTrackStore(private val context: Context) {
    fun loadTracks(): List<RecordedTrack> {
        return tracksDirectory()
            .listFiles { file -> file.extension.equals("gpx", ignoreCase = true) }
            ?.mapNotNull { file -> parseGpxTrack(file) }
            ?.sortedByDescending { it.startedAtMillis }
            ?: emptyList()
    }

    fun saveTrack(
        type: TrackType,
        points: List<TrackPoint>,
        distanceMeters: Float,
        startedAtMillis: Long,
        name: String = defaultTrackName(type, startedAtMillis)
    ): RecordedTrack {
        val file = File(
            tracksDirectory(),
            "track-${type.gpxType}-${FILE_NAME_FORMATTER.format(Instant.ofEpochMilli(startedAtMillis))}.gpx"
        )
        val track = RecordedTrack(
            name = name,
            type = type,
            points = points,
            distanceMeters = distanceMeters,
            durationMillis = calculateDuration(points),
            startedAtMillis = startedAtMillis,
            visible = true,
            file = file
        )
        writeTrack(track)
        return track
    }

    fun renameTrack(track: RecordedTrack, name: String): RecordedTrack {
        val updated = track.copy(name = name.ifBlank { defaultTrackName(track.type, track.startedAtMillis) })
        writeTrack(updated)
        return updated
    }

    fun setTrackVisibility(track: RecordedTrack, visible: Boolean): RecordedTrack {
        val updated = track.copy(visible = visible)
        writeTrack(updated)
        return updated
    }

    fun deleteTrack(track: RecordedTrack): Boolean {
        return track.file.delete()
    }

    fun saveImportedGpx(fileName: String, gpxBytes: ByteArray): RecordedTrack? {
        val safeFileName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(tracksDirectory(), safeFileName)
        file.writeBytes(gpxBytes)
        return parseGpxTrack(file)
    }

    private fun writeTrack(track: RecordedTrack) {
        track.file.outputStream().use { stream ->
            val serializer = Xml.newSerializer()
            serializer.setOutput(stream, Charsets.UTF_8.name())
            serializer.startDocument(Charsets.UTF_8.name(), true)
            serializer.startTag(null, "gpx")
            serializer.attribute(null, "version", "1.1")
            serializer.attribute(null, "creator", "ICU")
            serializer.attribute(null, "xmlns", "http://www.topografix.com/GPX/1/1")

            serializer.startTag(null, "metadata")
            serializer.startTag(null, "time")
            serializer.text(formatInstant(track.startedAtMillis))
            serializer.endTag(null, "time")
            serializer.endTag(null, "metadata")

            serializer.startTag(null, "trk")
            serializer.startTag(null, "name")
            serializer.text(track.name)
            serializer.endTag(null, "name")
            serializer.startTag(null, "type")
            serializer.text(track.type.gpxType)
            serializer.endTag(null, "type")
            serializer.startTag(null, "extensions")
            serializer.startTag(null, "distanceMeters")
            serializer.text(track.distanceMeters.roundToInt().toString())
            serializer.endTag(null, "distanceMeters")
            serializer.startTag(null, "visible")
            serializer.text(track.visible.toString())
            serializer.endTag(null, "visible")
            serializer.endTag(null, "extensions")

            serializer.startTag(null, "trkseg")
            track.points.forEach { point ->
                serializer.startTag(null, "trkpt")
                serializer.attribute(null, "lat", point.latitude.toString())
                serializer.attribute(null, "lon", point.longitude.toString())
                point.altitude?.let { altitude ->
                    serializer.startTag(null, "ele")
                    serializer.text(altitude.toString())
                    serializer.endTag(null, "ele")
                }
                serializer.startTag(null, "time")
                serializer.text(formatInstant(point.timeMillis))
                serializer.endTag(null, "time")
                serializer.endTag(null, "trkpt")
            }
            serializer.endTag(null, "trkseg")
            serializer.endTag(null, "trk")
            serializer.endTag(null, "gpx")
            serializer.endDocument()
        }
    }

    private fun parseGpxTrack(file: File): RecordedTrack? {
        val parser = Xml.newPullParser()
        file.inputStream().use { input ->
            parser.setInput(input, Charsets.UTF_8.name())
            var name: String? = null
            var type = TrackType.WALK
            var visible = true
            var storedDistanceMeters: Float? = null
            val points = mutableListOf<TrackPoint>()

            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue

                when (parser.name) {
                    "name" -> name = parser.readText()
                    "type" -> type = TrackType.fromGpxType(parser.readText())
                    "visible" -> visible = parser.readText().toBooleanStrictOrNull() ?: true
                    "distanceMeters" -> storedDistanceMeters = parser.readText().toFloatOrNull()
                    "trkpt" -> parseTrackPoint(parser)?.let { points.add(it) }
                }
            }

            if (points.isEmpty()) return null

            val startedAtMillis = points.first().timeMillis
            return RecordedTrack(
                name = name?.takeIf { it.isNotBlank() } ?: defaultTrackName(type, startedAtMillis),
                type = type,
                points = points,
                distanceMeters = storedDistanceMeters ?: calculateDistance(points),
                durationMillis = calculateDuration(points),
                startedAtMillis = startedAtMillis,
                visible = visible,
                file = file
            )
        }
    }

    private fun parseTrackPoint(parser: XmlPullParser): TrackPoint? {
        val latitude = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: return null
        val longitude = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: return null
        var altitude: Double? = null
        var timeMillis = System.currentTimeMillis()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.name == "trkpt") break
            if (parser.eventType != XmlPullParser.START_TAG) continue

            when (parser.name) {
                "ele" -> altitude = parser.readText().toDoubleOrNull()
                "time" -> timeMillis = runCatching {
                    Instant.parse(parser.readText()).toEpochMilli()
                }.getOrDefault(timeMillis)
            }
        }

        return TrackPoint(latitude, longitude, altitude, timeMillis)
    }

    private fun XmlPullParser.readText(): String {
        if (next() != XmlPullParser.TEXT) return ""
        val result = text
        nextTag()
        return result
    }

    private fun tracksDirectory(): File {
        return File(context.filesDir, "tracks").also { directory ->
            if (!directory.exists()) {
                directory.mkdirs()
            }
        }
    }

    companion object {
        private val FILE_NAME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss", Locale.US)
            .withZone(ZoneOffset.UTC)

        fun calculateDistance(points: List<TrackPoint>): Float {
            var distanceMeters = 0f
            points.zipWithNext { previous, current ->
                val result = FloatArray(1)
                Location.distanceBetween(
                    previous.latitude,
                    previous.longitude,
                    current.latitude,
                    current.longitude,
                    result
                )
                if (result[0] >= TrackRecordingService.MIN_DISTANCE_FOR_DISTANCE_METERS) {
                    distanceMeters += result[0]
                }
            }
            return distanceMeters
        }

        fun calculateDuration(points: List<TrackPoint>): Long {
            val first = points.firstOrNull()?.timeMillis ?: return 0L
            val last = points.lastOrNull()?.timeMillis ?: return 0L
            return (last - first).coerceAtLeast(0L)
        }

        fun monthKey(track: RecordedTrack): YearMonth {
            return YearMonth.from(
                Instant.ofEpochMilli(track.startedAtMillis).atZone(ZoneId.systemDefault())
            )
        }

        fun formatInstant(timeMillis: Long): String {
            return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(timeMillis))
        }

        fun defaultTrackName(type: TrackType, startedAtMillis: Long): String {
            val formatter = DateTimeFormatter
                .ofPattern("d MMMM, HH:mm", Locale.forLanguageTag("ru-RU"))
                .withZone(ZoneId.systemDefault())
            return "${type.title} ${formatter.format(Instant.ofEpochMilli(startedAtMillis))}"
        }
    }
}
