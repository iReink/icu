package com.example.icu

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ImportedWaypoint(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val timeMillis: Long?
)

data class ImportedTrack(
    val name: String?,
    val type: TrackType?,
    val points: List<TrackPoint>,
    val distanceMeters: Float?,
    val visible: Boolean
)

object GpxExchange {
    fun formatCoordinates(latitude: Double, longitude: Double): String {
        return String.format(Locale.US, "%.6f, %.6f", latitude, longitude)
    }

    fun exportDirectory(context: Context): File {
        return File(context.cacheDir, "exports").also { directory ->
            if (!directory.exists()) directory.mkdirs()
        }
    }

    fun safeFileName(name: String, extension: String): String {
        val base = name
            .trim()
            .ifBlank { "icu-export" }
            .replace(Regex("[^A-Za-z0-9А-Яа-я._ -]"), "_")
            .trim()
            .take(80)
            .ifBlank { "icu-export" }
        return if (base.endsWith(".$extension", ignoreCase = true)) base else "$base.$extension"
    }

    fun writeWaypointsToFile(file: File, points: List<ImportedWaypoint>) {
        file.outputStream().use { stream ->
            val serializer = Xml.newSerializer()
            serializer.setOutput(stream, Charsets.UTF_8.name())
            serializer.startDocument(Charsets.UTF_8.name(), true)
            serializer.startTag(null, "gpx")
            serializer.attribute(null, "version", "1.1")
            serializer.attribute(null, "creator", "ICU")
            serializer.attribute(null, "xmlns", "http://www.topografix.com/GPX/1/1")

            points.forEach { point ->
                serializer.startTag(null, "wpt")
                serializer.attribute(null, "lat", point.latitude.toString())
                serializer.attribute(null, "lon", point.longitude.toString())
                serializer.startTag(null, "name")
                serializer.text(point.name)
                serializer.endTag(null, "name")
                val time = point.timeMillis
                if (time != null) {
                    serializer.startTag(null, "time")
                    serializer.text(GpxTrackStore.formatInstant(time))
                    serializer.endTag(null, "time")
                }
                serializer.endTag(null, "wpt")
            }

            serializer.endTag(null, "gpx")
            serializer.endDocument()
        }
    }

    fun parseWaypoints(bytes: ByteArray): List<ImportedWaypoint> {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), Charsets.UTF_8.name())
        val points = mutableListOf<ImportedWaypoint>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "wpt") {
                parseWaypoint(parser)?.let(points::add)
            }
        }
        return points
    }

    fun parseTracks(bytes: ByteArray): List<ImportedTrack> {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), Charsets.UTF_8.name())
        val tracks = mutableListOf<ImportedTrack>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "trk") {
                parseTrack(parser)?.let(tracks::add)
            }
        }
        return tracks
    }

    fun zipTrackFiles(outputFile: File, tracks: List<RecordedTrack>) {
        ZipOutputStream(outputFile.outputStream()).use { zip ->
            tracks.forEachIndexed { index, track ->
                val entryName = safeFileName("${index + 1}-${track.name}", "gpx")
                zip.putNextEntry(ZipEntry(entryName))
                track.file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun unzipGpxFiles(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val files = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".gpx", ignoreCase = true)) {
                    val output = ByteArrayOutputStream()
                    zip.copyTo(output)
                    files.add(File(entry.name).name to output.toByteArray())
                }
                zip.closeEntry()
            }
        }
        return files
    }

    private fun parseWaypoint(parser: XmlPullParser): ImportedWaypoint? {
        val latitude = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: return null
        val longitude = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: return null
        var name = ""
        var timeMillis: Long? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.name == "wpt") break
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "name" -> name = parser.readTextSafely()
                "time" -> timeMillis = parseInstantMillis(parser.readTextSafely())
            }
        }
        return ImportedWaypoint(
            name = name.ifBlank { "Точка ${GpxTrackStore.defaultTrackName(TrackType.CUSTOM, System.currentTimeMillis()).substringAfter(' ')}" },
            latitude = latitude,
            longitude = longitude,
            timeMillis = timeMillis
        )
    }

    private fun parseTrack(parser: XmlPullParser): ImportedTrack? {
        var name: String? = null
        var type: TrackType? = null
        var visible = true
        var distanceMeters: Float? = null
        val points = mutableListOf<TrackPoint>()
        var nextPointStartsSegment = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.name == "trk") break
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "name" -> name = parser.readTextSafely()
                "type" -> type = TrackType.entries.firstOrNull { it.gpxType == parser.readTextSafely() }
                "visible" -> visible = parser.readTextSafely().toBooleanStrictOrNull() ?: true
                "distanceMeters" -> distanceMeters = parser.readTextSafely().toFloatOrNull()
                "trkseg" -> nextPointStartsSegment = points.isNotEmpty()
                "trkpt" -> parseTrackPoint(parser)?.let { point ->
                    points.add(point.copy(startsNewSegment = nextPointStartsSegment))
                    nextPointStartsSegment = false
                }
            }
        }
        if (points.isEmpty()) return null
        return ImportedTrack(name, type, points, distanceMeters, visible)
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
                "ele" -> altitude = parser.readTextSafely().toDoubleOrNull()
                "time" -> timeMillis = parseInstantMillis(parser.readTextSafely()) ?: timeMillis
            }
        }
        return TrackPoint(latitude, longitude, altitude, timeMillis)
    }

    private fun parseInstantMillis(value: String): Long? {
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
    }

    private fun XmlPullParser.readTextSafely(): String {
        if (next() != XmlPullParser.TEXT) return ""
        val result = text.orEmpty()
        nextTag()
        return result
    }
}
