package com.example.icu

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class TrackRecordingService : Service() {
    private lateinit var locationManager: LocationManager
    private lateinit var trackStore: GpxTrackStore
    private lateinit var liveLocationUploader: LiveLocationUploader

    private var recordingType: TrackType? = null
    private var recordingStartedAtMillis: Long = 0L
    private var recordingDistanceMeters: Float = 0f
    private var lastLiveLocationUploadMillis = 0L
    private val recordingPoints = mutableListOf<TrackPoint>()

    private val locationListener = LocationListener { location ->
        addRecordingLocation(location)
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        trackStore = GpxTrackStore(this)
        liveLocationUploader = LiveLocationUploader(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val type = TrackType.fromGpxType(intent.getStringExtra(EXTRA_TRACK_TYPE))
                startRecording(type)
            }
            ACTION_STOP -> finishRecording()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopLocationUpdates()
        if (currentState.isRecording) {
            currentState = RecordingState()
            broadcastStateChanged()
        }
        super.onDestroy()
    }

    private fun startRecording(type: TrackType) {
        if (currentState.isRecording || !hasLocationPermission()) return

        val provider = selectLocationProvider() ?: run {
            broadcastStateChanged()
            stopSelf()
            return
        }

        recordingType = type
        recordingStartedAtMillis = System.currentTimeMillis()
        recordingDistanceMeters = 0f
        lastLiveLocationUploadMillis = 0L
        recordingPoints.clear()
        currentState = RecordingState(
            isRecording = true,
            type = type,
            startedAtMillis = recordingStartedAtMillis,
            distanceMeters = recordingDistanceMeters,
            points = recordingPoints.toList()
        )

        startAsForeground()
        requestLocationUpdates(provider, type)
        broadcastStateChanged()
    }

    private fun finishRecording() {
        val type = recordingType
        val points = recordingPoints.toList()
        val startedAtMillis = recordingStartedAtMillis
        val distanceMeters = recordingDistanceMeters

        stopLocationUpdates()
        recordingType = null
        recordingStartedAtMillis = 0L
        recordingDistanceMeters = 0f
        lastLiveLocationUploadMillis = 0L
        recordingPoints.clear()

        if (type != null && points.isNotEmpty()) {
            trackStore.saveTrack(type, points, distanceMeters, startedAtMillis)
        }

        currentState = RecordingState()
        broadcastStateChanged()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun requestLocationUpdates(provider: String, type: TrackType) {
        try {
            locationManager.requestLocationUpdates(
                provider,
                recordingIntervalMs(type),
                0f,
                locationListener
            )
            locationManager.getLastKnownLocation(provider)
                ?.takeIf { location -> System.currentTimeMillis() - location.time <= MAX_LAST_KNOWN_LOCATION_AGE_MS }
                ?.let { addRecordingLocation(it) }
        } catch (_: SecurityException) {
            finishRecording()
        }
    }

    private fun stopLocationUpdates() {
        locationManager.removeUpdates(locationListener)
    }

    private fun addRecordingLocation(location: Location) {
        val type = recordingType ?: return
        if (!isUsableTrackLocation(location)) return

        val point = TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else null,
            timeMillis = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        )

        val previous = recordingPoints.lastOrNull()
        if (previous != null) {
            val distance = segmentDistanceMeters(previous, point)
            if (isMeaningfulMovement(previous, point, distance)) {
                recordingDistanceMeters += distance
            }
        }

        recordingPoints.add(point)
        uploadLiveLocationIfNeeded(location)
        currentState = RecordingState(
            isRecording = true,
            type = type,
            startedAtMillis = recordingStartedAtMillis,
            distanceMeters = recordingDistanceMeters,
            points = recordingPoints.toList()
        )
        updateNotification()
        broadcastStateChanged()
    }

    private fun uploadLiveLocationIfNeeded(location: Location) {
        val now = System.currentTimeMillis()
        if (now - lastLiveLocationUploadMillis < LIVE_LOCATION_INTERVAL_MS) return
        lastLiveLocationUploadMillis = now
        Thread {
            liveLocationUploader.enqueueAndFlush(location)
        }.start()
    }

    private fun isUsableTrackLocation(location: Location): Boolean {
        return isUsableLocation(location)
    }

    private fun selectLocationProvider(): String? {
        return when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
    }

    private fun recordingIntervalMs(type: TrackType): Long {
        if (RecordingPreferences.isHighAccuracyEnabled(this)) return HIGH_ACCURACY_INTERVAL_MS
        return when (type) {
            TrackType.WALK -> WALK_INTERVAL_MS
            TrackType.BIKE -> BIKE_INTERVAL_MS
        }
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

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(formatNotificationText())
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setWhen(recordingStartedAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.recording_notification_stop),
                stopPendingIntent()
            )
            .build()

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, TrackRecordingService::class.java).apply {
            action = ACTION_STOP
        }
        return PendingIntent.getService(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun formatNotificationText(): String {
        return String.format(Locale.forLanguageTag("ru-RU"), "%.2f км", recordingDistanceMeters / 1000f)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Запись трека",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun broadcastStateChanged() {
        val intent = Intent(ACTION_STATE_CHANGED).setPackage(packageName)
        sendBroadcast(intent)
    }

    companion object {
        const val ACTION_START = "com.example.icu.action.START_RECORDING"
        const val ACTION_STOP = "com.example.icu.action.STOP_RECORDING"
        const val ACTION_STATE_CHANGED = "com.example.icu.action.RECORDING_STATE_CHANGED"
        const val EXTRA_TRACK_TYPE = "track_type"
        const val HIGH_ACCURACY_INTERVAL_MS = 1_000L
        const val WALK_INTERVAL_MS = 3_000L
        const val BIKE_INTERVAL_MS = 2_000L
        const val MAX_ACCEPTED_ACCURACY_METERS = 50f
        const val MIN_MOVEMENT_METERS_PER_SECOND = 0.5f
        const val LIVE_LOCATION_INTERVAL_MS = 10_000L

        private const val CHANNEL_ID = "track_recording"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_LAST_KNOWN_LOCATION_AGE_MS = 120_000L

        @Volatile
        var currentState: RecordingState = RecordingState()
            private set

        fun isUsableLocation(location: Location): Boolean {
            return !location.hasAccuracy() || location.accuracy <= MAX_ACCEPTED_ACCURACY_METERS
        }

        fun segmentDistanceMeters(previous: TrackPoint, current: TrackPoint): Float {
            val distance = FloatArray(1)
            Location.distanceBetween(
                previous.latitude,
                previous.longitude,
                current.latitude,
                current.longitude,
                distance
            )
            return distance[0]
        }

        fun isMeaningfulMovement(
            previous: TrackPoint,
            current: TrackPoint,
            distanceMeters: Float = segmentDistanceMeters(previous, current)
        ): Boolean {
            return distanceMeters > movementNoiseThresholdMeters(previous, current)
        }

        fun movementNoiseThresholdMeters(previous: TrackPoint, current: TrackPoint): Float {
            val elapsedMillis = (current.timeMillis - previous.timeMillis).coerceAtLeast(0L)
            val elapsedSeconds = if (elapsedMillis > 0L) {
                elapsedMillis / 1000f
            } else {
                HIGH_ACCURACY_INTERVAL_MS / 1000f
            }
            return MIN_MOVEMENT_METERS_PER_SECOND * elapsedSeconds
        }
    }
}
