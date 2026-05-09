package com.example.icu

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Xml
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private enum class TrackType(
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

    private data class TrackPoint(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double?,
        val timeMillis: Long
    ) {
        fun toGeoPoint(): GeoPoint = GeoPoint(latitude, longitude)
    }

    private data class RecordedTrack(
        val type: TrackType,
        val points: List<TrackPoint>,
        val distanceMeters: Float,
        val startedAtMillis: Long,
        val file: File
    )

    private lateinit var map: MapView
    private lateinit var recordingPanel: View
    private lateinit var distanceText: TextView
    private lateinit var durationText: TextView
    private lateinit var addTrackFab: FloatingActionButton
    private lateinit var myLocationButton: FloatingActionButton
    private lateinit var locationManager: LocationManager

    private var locationOverlay: MyLocationNewOverlay? = null
    private var addTrackSheet: BottomSheetDialog? = null
    private var pendingStartType: TrackType? = null
    private var recordingType: TrackType? = null
    private var recordingStartedAtMillis: Long = 0L
    private var recordingDistanceMeters: Float = 0f
    private var recordingPoints = mutableListOf<TrackPoint>()
    private var recordingPolyline: Polyline? = null

    private val elapsedHandler = Handler(Looper.getMainLooper())
    private val elapsedTicker = object : Runnable {
        override fun run() {
            updateRecordingPanel()
            elapsedHandler.postDelayed(this, TIMER_INTERVAL_MS)
        }
    }

    private val trackLocationListener = LocationListener { location ->
        addRecordingLocation(location)
    }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val isGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (isGranted) {
                enableMyLocation()
                pendingStartType?.let { type ->
                    pendingStartType = null
                    startRecording(type)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        map = findViewById(R.id.map)
        recordingPanel = findViewById(R.id.recordingPanel)
        distanceText = findViewById(R.id.distanceText)
        durationText = findViewById(R.id.durationText)
        addTrackFab = findViewById(R.id.addTrackFab)
        myLocationButton = findViewById(R.id.myLocationButton)

        setupMap()
        loadSavedTracks()

        myLocationButton.setOnClickListener {
            if (hasLocationPermission()) {
                enableMyLocation()
            } else {
                requestLocationPermission()
            }
        }

        addTrackFab.setOnClickListener {
            showAddTrackSheet()
        }

        findViewById<MaterialButton>(R.id.finishRecordingButton).setOnClickListener {
            finishRecording()
        }

        if (hasLocationPermission()) {
            enableMyLocation()
        } else {
            requestLocationPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        locationOverlay?.enableMyLocation()
        if (recordingType != null) {
            elapsedHandler.post(elapsedTicker)
        }
    }

    override fun onPause() {
        locationOverlay?.disableMyLocation()
        elapsedHandler.removeCallbacks(elapsedTicker)
        map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        stopLocationUpdates()
        elapsedHandler.removeCallbacks(elapsedTicker)
        super.onDestroy()
    }

    private fun setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.minZoomLevel = 3.0
        map.maxZoomLevel = 20.0

        val startPoint = GeoPoint(56.8389, 60.6057)
        map.controller.setZoom(15.0)
        map.controller.setCenter(startPoint)
    }

    private fun enableMyLocation() {
        if (!hasLocationPermission()) return

        if (locationOverlay == null) {
            locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map).also { overlay ->
                overlay.enableMyLocation()
                overlay.enableFollowLocation()
                overlay.runOnFirstFix {
                    runOnUiThread {
                        overlay.myLocation?.let { location ->
                            map.controller.animateTo(location)
                            map.controller.setZoom(17.0)
                        }
                    }
                }
                map.overlays.add(overlay)
            }
        } else {
            locationOverlay?.enableMyLocation()
            locationOverlay?.enableFollowLocation()
        }

        map.invalidate()
    }

    private fun showAddTrackSheet() {
        val sheet = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_add_track, null)
        sheet.setContentView(content)
        sheet.setOnShowListener { dialog ->
            val bottomSheet = (dialog as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.background = ColorDrawable(Color.TRANSPARENT)
        }

        content.findViewById<MaterialButton>(R.id.walkTrackButton).setOnClickListener {
            sheet.dismiss()
            requestStartRecording(TrackType.WALK)
        }
        content.findViewById<MaterialButton>(R.id.bikeTrackButton).setOnClickListener {
            sheet.dismiss()
            requestStartRecording(TrackType.BIKE)
        }
        content.findViewById<MaterialButton>(R.id.uploadTrackButton).setOnClickListener {
            Toast.makeText(this, R.string.gpx_upload_mock, Toast.LENGTH_SHORT).show()
        }

        addTrackSheet = sheet
        sheet.show()
    }

    private fun requestStartRecording(type: TrackType) {
        if (recordingType != null) {
            Toast.makeText(this, R.string.recording_already_started, Toast.LENGTH_SHORT).show()
            return
        }

        if (hasLocationPermission()) {
            startRecording(type)
        } else {
            pendingStartType = type
            requestLocationPermission()
        }
    }

    private fun startRecording(type: TrackType) {
        if (!hasLocationPermission()) return

        recordingType = type
        recordingStartedAtMillis = System.currentTimeMillis()
        recordingDistanceMeters = 0f
        recordingPoints = mutableListOf()

        recordingPolyline = createTrackPolyline(type).also { polyline ->
            map.overlays.add(polyline)
        }

        if (!startLocationUpdates()) {
            recordingType = null
            recordingPolyline?.let { map.overlays.remove(it) }
            recordingPolyline = null
            recordingPoints = mutableListOf()
            map.invalidate()
            return
        }

        recordingPanel.visibility = View.VISIBLE
        addTrackFab.visibility = View.GONE
        updateRecordingPanel()
        elapsedHandler.removeCallbacks(elapsedTicker)
        elapsedHandler.post(elapsedTicker)
    }

    private fun finishRecording() {
        val type = recordingType ?: return
        stopLocationUpdates()
        elapsedHandler.removeCallbacks(elapsedTicker)

        val points = recordingPoints.toList()
        val polyline = recordingPolyline
        recordingType = null
        recordingPolyline = null
        recordingPoints = mutableListOf()
        recordingPanel.visibility = View.GONE
        addTrackFab.visibility = View.VISIBLE

        if (points.isEmpty()) {
            polyline?.let { map.overlays.remove(it) }
            map.invalidate()
            Toast.makeText(this, R.string.track_without_points, Toast.LENGTH_SHORT).show()
            return
        }

        val file = saveTrackAsGpx(type, points, recordingDistanceMeters, recordingStartedAtMillis)
        Toast.makeText(this, getString(R.string.track_saved, file.name), Toast.LENGTH_SHORT).show()
        map.invalidate()
    }

    private fun startLocationUpdates(): Boolean {
        if (!hasLocationPermission()) return false

        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }

        if (provider == null) {
            Toast.makeText(this, R.string.location_provider_disabled, Toast.LENGTH_LONG).show()
            return false
        }

        try {
            locationManager.requestLocationUpdates(
                provider,
                LOCATION_INTERVAL_MS,
                0f,
                trackLocationListener
            )
            locationManager.getLastKnownLocation(provider)?.let { addRecordingLocation(it) }
        } catch (securityException: SecurityException) {
            Toast.makeText(this, R.string.location_permission_denied, Toast.LENGTH_SHORT).show()
            return false
        }

        return true
    }

    private fun stopLocationUpdates() {
        locationManager.removeUpdates(trackLocationListener)
    }

    private fun addRecordingLocation(location: Location) {
        val type = recordingType ?: return
        if (!isUsableTrackLocation(location)) return

        val point = TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else null,
            timeMillis = location.time
        )

        val previous = recordingPoints.lastOrNull()
        if (previous != null) {
            val distance = FloatArray(1)
            Location.distanceBetween(
                previous.latitude,
                previous.longitude,
                point.latitude,
                point.longitude,
                distance
            )
            if (distance[0] >= MIN_DISTANCE_FOR_DISTANCE_METERS) {
                recordingDistanceMeters += distance[0]
            }
        }

        recordingPoints.add(point)
        recordingPolyline?.setPoints(recordingPoints.map { it.toGeoPoint() })
        updateRecordingPanel()

        if (recordingPoints.size == 1) {
            map.controller.animateTo(point.toGeoPoint())
            map.controller.setZoom(17.0)
        }

        map.invalidate()
    }

    private fun isUsableTrackLocation(location: Location): Boolean {
        if (location.hasAccuracy() && location.accuracy > MAX_ACCEPTED_ACCURACY_METERS) {
            return false
        }
        return true
    }

    private fun updateRecordingPanel() {
        distanceText.text = formatDistance(recordingDistanceMeters)
        val elapsedMillis = (System.currentTimeMillis() - recordingStartedAtMillis).coerceAtLeast(0L)
        durationText.text = formatDuration(elapsedMillis)
    }

    private fun loadSavedTracks() {
        tracksDirectory()
            .listFiles { file -> file.extension.equals("gpx", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                parseGpxTrack(file)?.let { track ->
                    if (track.points.isNotEmpty()) {
                        val polyline = createTrackPolyline(track.type)
                        polyline.setPoints(track.points.map { it.toGeoPoint() })
                        map.overlays.add(polyline)
                    }
                }
            }
        map.invalidate()
    }

    private fun createTrackPolyline(type: TrackType): Polyline {
        return Polyline(map).apply {
            outlinePaint.color = type.color
            outlinePaint.strokeWidth = TRACK_STROKE_WIDTH
            outlinePaint.isAntiAlias = true
        }
    }

    private fun saveTrackAsGpx(
        type: TrackType,
        points: List<TrackPoint>,
        distanceMeters: Float,
        startedAtMillis: Long
    ): File {
        val file = File(
            tracksDirectory(),
            "track-${type.gpxType}-${DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
                .withZone(java.time.ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(startedAtMillis))}.gpx"
        )

        file.outputStream().use { stream ->
            val serializer = Xml.newSerializer()
            serializer.setOutput(stream, Charsets.UTF_8.name())
            serializer.startDocument(Charsets.UTF_8.name(), true)
            serializer.startTag(null, "gpx")
            serializer.attribute(null, "version", "1.1")
            serializer.attribute(null, "creator", "ICU")
            serializer.attribute(null, "xmlns", "http://www.topografix.com/GPX/1/1")

            serializer.startTag(null, "metadata")
            serializer.startTag(null, "time")
            serializer.text(formatInstant(startedAtMillis))
            serializer.endTag(null, "time")
            serializer.endTag(null, "metadata")

            serializer.startTag(null, "trk")
            serializer.startTag(null, "name")
            serializer.text("${type.title} ${formatInstant(startedAtMillis)}")
            serializer.endTag(null, "name")
            serializer.startTag(null, "type")
            serializer.text(type.gpxType)
            serializer.endTag(null, "type")
            serializer.startTag(null, "extensions")
            serializer.startTag(null, "distanceMeters")
            serializer.text(distanceMeters.roundToInt().toString())
            serializer.endTag(null, "distanceMeters")
            serializer.endTag(null, "extensions")

            serializer.startTag(null, "trkseg")
            points.forEach { point ->
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

        return file
    }

    private fun parseGpxTrack(file: File): RecordedTrack? {
        val parser = Xml.newPullParser()
        file.inputStream().use { input ->
            parser.setInput(input, Charsets.UTF_8.name())
            var type = TrackType.WALK
            val points = mutableListOf<TrackPoint>()

            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue

                when (parser.name) {
                    "type" -> type = TrackType.fromGpxType(parser.readText())
                    "trkpt" -> parseTrackPoint(parser)?.let { points.add(it) }
                }
            }

            return RecordedTrack(
                type = type,
                points = points,
                distanceMeters = calculateDistance(points),
                startedAtMillis = points.firstOrNull()?.timeMillis ?: file.lastModified(),
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
        return File(filesDir, "tracks").also { directory ->
            if (!directory.exists()) {
                directory.mkdirs()
            }
        }
    }

    private fun calculateDistance(points: List<TrackPoint>): Float {
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
            if (result[0] >= MIN_DISTANCE_FOR_DISTANCE_METERS) {
                distanceMeters += result[0]
            }
        }
        return distanceMeters
    }

    private fun formatDistance(meters: Float): String {
        return String.format(Locale.forLanguageTag("ru-RU"), "%.2f км", meters / 1000f)
    }

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun formatInstant(timeMillis: Long): String {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(timeMillis))
    }

    private fun hasLocationPermission(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineLocation || coarseLocation
    }

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    companion object {
        private const val LOCATION_INTERVAL_MS = 5_000L
        private const val TIMER_INTERVAL_MS = 1_000L
        private const val MAX_ACCEPTED_ACCURACY_METERS = 50f
        private const val MIN_DISTANCE_FOR_DISTANCE_METERS = 3f
        private const val TRACK_STROKE_WIDTH = 8f
    }
}
