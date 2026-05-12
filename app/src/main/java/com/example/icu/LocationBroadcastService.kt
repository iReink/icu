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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.max

data class LocationBroadcastState(
    val isActive: Boolean = false,
    val startedAtMillis: Long = 0L,
    val endsAtMillis: Long? = null
)

class LocationBroadcastService : Service() {
    private lateinit var locationManager: LocationManager
    private lateinit var liveLocationUploader: LiveLocationUploader
    private val handler = Handler(Looper.getMainLooper())

    private var lastGoodPoint: LocationSharePoint? = null
    private var lastUploadMillis = 0L

    private val stopRunnable = Runnable {
        stopBroadcast()
    }

    private val locationListener = LocationListener { location ->
        handleLocation(location)
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        liveLocationUploader = LiveLocationUploader(this)
        createNotificationChannel()
        currentState = loadState(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBroadcast(intent.durationMsOrNull())
            ACTION_STOP -> stopBroadcast()
            else -> if (currentState.isActive) resumeBroadcast()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(stopRunnable)
        stopLocationUpdates()
        if (currentState.isActive) {
            saveState(this, LocationBroadcastState())
            currentState = LocationBroadcastState()
            broadcastStateChanged()
        }
        super.onDestroy()
    }

    private fun startBroadcast(durationMs: Long?) {
        if (currentState.isActive || !hasLocationPermission() || SupabaseSessionStore(this).current() == null) {
            if (!currentState.isActive) stopSelf()
            return
        }

        val provider = selectLocationProvider() ?: run {
            stopSelf()
            return
        }

        val now = System.currentTimeMillis()
        currentState = LocationBroadcastState(
            isActive = true,
            startedAtMillis = now,
            endsAtMillis = durationMs?.let { now + it }
        )
        saveState(this, currentState)
        lastGoodPoint = loadLastPoint(this)
        lastUploadMillis = 0L

        startAsForeground()
        requestLocationUpdates(provider)
        scheduleStopIfNeeded()
        broadcastStateChanged()
    }

    private fun resumeBroadcast() {
        if (!hasLocationPermission() || SupabaseSessionStore(this).current() == null || isExpired()) {
            stopBroadcast()
            return
        }
        val provider = selectLocationProvider() ?: run {
            stopBroadcast()
            return
        }
        lastGoodPoint = lastGoodPoint ?: loadLastPoint(this)
        startAsForeground()
        requestLocationUpdates(provider)
        scheduleStopIfNeeded()
        broadcastStateChanged()
    }

    private fun stopBroadcast() {
        handler.removeCallbacks(stopRunnable)
        stopLocationUpdates()
        saveState(this, LocationBroadcastState())
        currentState = LocationBroadcastState()
        lastGoodPoint = null
        lastUploadMillis = 0L
        broadcastStateChanged()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun requestLocationUpdates(provider: String) {
        try {
            locationManager.requestLocationUpdates(
                provider,
                LIVE_LOCATION_BROADCAST_INTERVAL_MS,
                0f,
                locationListener
            )
            locationManager.getLastKnownLocation(provider)
                ?.takeIf { System.currentTimeMillis() - it.time <= MAX_LAST_KNOWN_LOCATION_AGE_MS }
                ?.let { handleLocation(it) }
        } catch (_: SecurityException) {
            stopBroadcast()
        }
    }

    private fun stopLocationUpdates() {
        runCatching { locationManager.removeUpdates(locationListener) }
    }

    private fun handleLocation(location: Location) {
        if (isExpired()) {
            stopBroadcast()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastUploadMillis < LIVE_LOCATION_BROADCAST_INTERVAL_MS) return

        val rawPoint = location.toSharePoint()
        val candidate = if (TrackRecordingService.isUsableLocation(location) || isLargeMoveDespitePoorAccuracy(rawPoint)) {
            rawPoint
        } else null

        val pointToUpload = when {
            candidate == null -> lastGoodPoint?.copy(recordedAtMillis = now)
            lastGoodPoint == null -> candidate.copy(recordedAtMillis = now).also { rememberLastGoodPoint(it) }
            distanceMeters(lastGoodPoint!!, candidate) >= BROADCAST_NOISE_THRESHOLD_METERS -> {
                candidate.copy(recordedAtMillis = now).also { rememberLastGoodPoint(it) }
            }
            else -> lastGoodPoint?.copy(recordedAtMillis = now)
        } ?: return

        lastUploadMillis = now
        Thread {
            liveLocationUploader.enqueueAndFlush(pointToUpload)
        }.start()
        updateNotification()
    }

    private fun isLargeMoveDespitePoorAccuracy(point: LocationSharePoint): Boolean {
        val accuracy = point.accuracyMeters ?: return false
        if (accuracy <= TrackRecordingService.MAX_ACCEPTED_ACCURACY_METERS) return true
        val previous = lastGoodPoint ?: return false
        return distanceMeters(previous, point) > accuracy * INACCURATE_LOCATION_DISTANCE_MULTIPLIER
    }

    private fun rememberLastGoodPoint(point: LocationSharePoint) {
        lastGoodPoint = point
        saveLastPoint(this, point)
    }

    private fun isExpired(): Boolean {
        val endsAt = currentState.endsAtMillis ?: return false
        return System.currentTimeMillis() >= endsAt
    }

    private fun scheduleStopIfNeeded() {
        handler.removeCallbacks(stopRunnable)
        val endsAt = currentState.endsAtMillis ?: return
        val delay = max(0L, endsAt - System.currentTimeMillis())
        handler.postDelayed(stopRunnable, delay)
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

    private fun selectLocationProvider(): String? {
        return when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
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
            .setContentTitle(getString(R.string.location_broadcast_notification_title))
            .setContentText(notificationText())
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.recording_notification_stop),
                stopPendingIntent()
            )
            .build()

    private fun notificationText(): String {
        val endsAt = currentState.endsAtMillis ?: return getString(R.string.location_broadcast_until_manual)
        val remaining = (endsAt - System.currentTimeMillis()).coerceAtLeast(0L)
        return getString(R.string.location_broadcast_notification_time, formatRemainingTime(remaining))
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            2,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, LocationBroadcastService::class.java).apply {
            action = ACTION_STOP
        }
        return PendingIntent.getService(
            this,
            2,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.location_broadcast_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun broadcastStateChanged() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun Intent.durationMsOrNull(): Long? {
        return if (hasExtra(EXTRA_DURATION_MS)) getLongExtra(EXTRA_DURATION_MS, 0L) else null
    }

    companion object {
        const val ACTION_START = "com.example.icu.action.START_LOCATION_BROADCAST"
        const val ACTION_STOP = "com.example.icu.action.STOP_LOCATION_BROADCAST"
        const val ACTION_STATE_CHANGED = "com.example.icu.action.LOCATION_BROADCAST_STATE_CHANGED"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val LIVE_LOCATION_BROADCAST_INTERVAL_MS = 15_000L
        const val BROADCAST_NOISE_THRESHOLD_METERS = 5f
        const val INACCURATE_LOCATION_DISTANCE_MULTIPLIER = 3f

        private const val CHANNEL_ID = "location_broadcast"
        private const val NOTIFICATION_ID = 1002
        private const val MAX_LAST_KNOWN_LOCATION_AGE_MS = 120_000L
        private const val PREFS_NAME = "location_broadcast"
        private const val KEY_ACTIVE = "active"
        private const val KEY_STARTED_AT = "started_at"
        private const val KEY_ENDS_AT = "ends_at"
        private const val KEY_LAST_LATITUDE = "last_latitude"
        private const val KEY_LAST_LONGITUDE = "last_longitude"
        private const val KEY_LAST_ALTITUDE = "last_altitude"
        private const val KEY_LAST_ACCURACY = "last_accuracy"
        private const val KEY_LAST_RECORDED_AT = "last_recorded_at"
        private const val NO_END = -1L
        private const val NO_DOUBLE = Double.NaN
        private const val NO_FLOAT = Float.NaN

        @Volatile
        var currentState: LocationBroadcastState = LocationBroadcastState()
            private set

        fun state(context: Context): LocationBroadcastState {
            val state = loadState(context)
            currentState = state
            return state
        }

        fun formatRemainingTime(remainingMillis: Long): String {
            val totalMinutes = (remainingMillis / 60_000L).coerceAtLeast(1L)
            val hours = totalMinutes / 60L
            val minutes = totalMinutes % 60L
            return when {
                hours <= 0L -> "$minutes мин"
                minutes <= 0L -> "$hours ч"
                else -> "$hours ч $minutes мин"
            }
        }

        private fun loadState(context: Context): LocationBroadcastState {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_ACTIVE, false)) return LocationBroadcastState()
            val endsAt = prefs.getLong(KEY_ENDS_AT, NO_END).takeIf { it != NO_END }
            if (endsAt != null && System.currentTimeMillis() >= endsAt) {
                saveState(context, LocationBroadcastState())
                return LocationBroadcastState()
            }
            return LocationBroadcastState(
                isActive = true,
                startedAtMillis = prefs.getLong(KEY_STARTED_AT, 0L),
                endsAtMillis = endsAt
            )
        }

        private fun saveState(context: Context, state: LocationBroadcastState) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ACTIVE, state.isActive)
                .putLong(KEY_STARTED_AT, state.startedAtMillis)
                .putLong(KEY_ENDS_AT, state.endsAtMillis ?: NO_END)
                .apply()
        }

        private fun loadLastPoint(context: Context): LocationSharePoint? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

        private fun saveLastPoint(context: Context, point: LocationSharePoint) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_LATITUDE, point.latitude.toBits())
                .putLong(KEY_LAST_LONGITUDE, point.longitude.toBits())
                .putFloat(KEY_LAST_ALTITUDE, point.altitude?.toFloat() ?: NO_FLOAT)
                .putFloat(KEY_LAST_ACCURACY, point.accuracyMeters ?: NO_FLOAT)
                .putLong(KEY_LAST_RECORDED_AT, point.recordedAtMillis)
                .apply()
        }
    }
}
