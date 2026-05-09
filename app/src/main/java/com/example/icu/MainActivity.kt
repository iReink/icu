package com.example.icu

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import com.google.android.material.switchmaterial.SwitchMaterial
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var map: MapView
    private lateinit var recordingPanel: View
    private lateinit var distanceText: TextView
    private lateinit var durationText: TextView
    private lateinit var addTrackFab: FloatingActionButton
    private lateinit var myLocationButton: FloatingActionButton
    private lateinit var menuButton: FloatingActionButton
    private lateinit var sectionPanel: LinearLayout
    private lateinit var sectionTitle: TextView
    private lateinit var sectionContent: LinearLayout
    private lateinit var trackStore: GpxTrackStore

    private var locationOverlay: MyLocationNewOverlay? = null
    private var addTrackSheet: BottomSheetDialog? = null
    private var pendingStartType: TrackType? = null
    private var savedTrackOverlays = mutableListOf<Polyline>()
    private var activeTrackPolyline: Polyline? = null
    private var isReceiverRegistered = false

    private val elapsedHandler = Handler(Looper.getMainLooper())
    private val elapsedTicker = object : Runnable {
        override fun run() {
            updateRecordingPanelTime()
            elapsedHandler.postDelayed(this, TIMER_INTERVAL_MS)
        }
    }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val isGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (isGranted) {
                enableMyLocation()
                continuePendingRecordingStart()
            } else {
                pendingStartType = null
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startPendingRecording()
        }

    private val recordingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            syncRecordingState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = packageName
        trackStore = GpxTrackStore(this)
        setContentView(R.layout.activity_main)

        bindViews()
        setupMap()
        setupDrawer()
        setupActions()
        loadSavedTracks()
        syncRecordingState()

        if (hasLocationPermission()) {
            enableMyLocation()
        } else {
            requestLocationPermission()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    sectionPanel.visibility == View.VISIBLE -> hideSection()
                    drawerLayout.isDrawerOpen(GravityCompat.END) -> drawerLayout.closeDrawer(GravityCompat.END)
                    else -> finish()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        registerRecordingReceiver()
        locationOverlay?.enableMyLocation()
        loadSavedTracks()
        syncRecordingState()
    }

    override fun onPause() {
        locationOverlay?.disableMyLocation()
        unregisterRecordingReceiver()
        map.onPause()
        super.onPause()
        elapsedHandler.removeCallbacks(elapsedTicker)
    }

    private fun bindViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        navigationView = findViewById(R.id.navigationView)
        map = findViewById(R.id.map)
        recordingPanel = findViewById(R.id.recordingPanel)
        distanceText = findViewById(R.id.distanceText)
        durationText = findViewById(R.id.durationText)
        addTrackFab = findViewById(R.id.addTrackFab)
        myLocationButton = findViewById(R.id.myLocationButton)
        menuButton = findViewById(R.id.menuButton)
        sectionPanel = findViewById(R.id.sectionPanel)
        sectionTitle = findViewById(R.id.sectionTitle)
        sectionContent = findViewById(R.id.sectionContent)
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

    private fun setupDrawer() {
        navigationView.setNavigationItemSelectedListener { item ->
            drawerLayout.closeDrawer(GravityCompat.END)
            when (item.itemId) {
                R.id.menuMyTracks -> showTracksScreen()
                R.id.menuStatistics -> showStatisticsScreen()
            }
            true
        }
    }

    private fun setupActions() {
        menuButton.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.END)
        }

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
            stopRecordingService()
        }

        findViewById<MaterialButton>(R.id.closeSectionButton).setOnClickListener {
            hideSection()
        }
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
        if (TrackRecordingService.currentState.isRecording) {
            Toast.makeText(this, R.string.recording_already_started, Toast.LENGTH_SHORT).show()
            return
        }

        pendingStartType = type

        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }

        if (!hasNotificationPermission()) {
            requestNotificationPermission()
            return
        }

        startPendingRecording()
    }

    private fun continuePendingRecordingStart() {
        if (!hasLocationPermission()) return
        if (!hasNotificationPermission()) {
            requestNotificationPermission()
            return
        }

        startPendingRecording()
    }

    private fun startPendingRecording() {
        val type = pendingStartType ?: return
        if (!hasLocationPermission()) return

        pendingStartType = null
        val intent = Intent(this, TrackRecordingService::class.java).apply {
            action = TrackRecordingService.ACTION_START
            putExtra(TrackRecordingService.EXTRA_TRACK_TYPE, type.gpxType)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopRecordingService() {
        val intent = Intent(this, TrackRecordingService::class.java).apply {
            action = TrackRecordingService.ACTION_STOP
        }
        startService(intent)
    }

    private fun syncRecordingState() {
        val state = TrackRecordingService.currentState
        val isRecording = state.isRecording

        recordingPanel.visibility = if (isRecording) View.VISIBLE else View.GONE
        addTrackFab.visibility = if (isRecording) View.GONE else View.VISIBLE
        menuButton.visibility = if (isRecording) View.GONE else View.VISIBLE

        if (isRecording) {
            updateRecordingPanelTime()
            drawActiveTrack(state)
            elapsedHandler.removeCallbacks(elapsedTicker)
            elapsedHandler.post(elapsedTicker)
        } else {
            elapsedHandler.removeCallbacks(elapsedTicker)
            activeTrackPolyline?.let { map.overlays.remove(it) }
            activeTrackPolyline = null
            loadSavedTracks()
        }

        map.invalidate()
    }

    private fun updateRecordingPanelTime() {
        val state = TrackRecordingService.currentState
        if (!state.isRecording) return
        distanceText.text = formatDistance(state.distanceMeters)
        durationText.text = formatDuration((System.currentTimeMillis() - state.startedAtMillis).coerceAtLeast(0L))
    }

    private fun drawActiveTrack(state: RecordingState) {
        val type = state.type ?: return
        val polyline = activeTrackPolyline ?: createTrackPolyline(type).also { newPolyline ->
            activeTrackPolyline = newPolyline
            map.overlays.add(newPolyline)
        }
        polyline.outlinePaint.color = type.color
        polyline.setPoints(state.points.map { it.toGeoPoint() })

        state.points.lastOrNull()?.let { point ->
            map.controller.animateTo(point.toGeoPoint())
        }
    }

    private fun loadSavedTracks() {
        savedTrackOverlays.forEach { map.overlays.remove(it) }
        savedTrackOverlays.clear()

        trackStore.loadTracks()
            .filter { it.visible }
            .forEach { track ->
                val polyline = createTrackPolyline(track.type)
                polyline.setPoints(track.points.map { it.toGeoPoint() })
                savedTrackOverlays.add(polyline)
                map.overlays.add(polyline)
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

    private fun showTracksScreen() {
        val tracks = trackStore.loadTracks()
        showSection(getString(R.string.my_tracks))

        if (tracks.isEmpty()) {
            sectionContent.addView(emptyStateText(getString(R.string.no_tracks)))
            return
        }

        tracks.groupBy { GpxTrackStore.monthKey(it) }
            .toSortedMap(compareByDescending<YearMonth> { it.year }.thenByDescending { it.monthValue })
            .forEach { (month, monthTracks) ->
                sectionContent.addView(groupTitle(formatMonthTitle(month)))
                monthTracks.sortedByDescending { it.startedAtMillis }.forEach { track ->
                    sectionContent.addView(trackCard(track))
                }
            }
    }

    private fun showStatisticsScreen() {
        val tracks = trackStore.loadTracks()
        showSection(getString(R.string.statistics))
        sectionContent.addView(statSummaryCard(tracks))
        sectionContent.addView(activityStatsCard(TrackType.WALK, tracks))
        sectionContent.addView(activityStatsCard(TrackType.BIKE, tracks))
    }

    private fun showSection(title: String) {
        sectionTitle.text = title
        sectionContent.removeAllViews()
        sectionPanel.visibility = View.VISIBLE
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
    }

    private fun hideSection() {
        sectionPanel.visibility = View.GONE
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
    }

    private fun groupTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(18)
                bottomMargin = dp(10)
            }
        }
    }

    private fun trackCard(track: RecordedTrack): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_content_card)
            setPadding(dp(16), dp(14), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val colorMark = View(this).apply {
            setBackgroundColor(track.type.color)
            layoutParams = LinearLayout.LayoutParams(dp(4), dp(42)).apply {
                rightMargin = dp(12)
            }
        }
        header.addView(colorMark)

        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleColumn.addView(TextView(this).apply {
            text = track.name
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        })
        titleColumn.addView(TextView(this).apply {
            text = "${track.type.title} · ${formatTrackDate(track.startedAtMillis)}"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_secondary))
            textSize = 14f
        })
        header.addView(titleColumn)
        card.addView(header)

        card.addView(TextView(this).apply {
            text = "${formatDistance(track.distanceMeters)} · ${formatDuration(track.durationMillis)}"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_primary))
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        })

        val visibilitySwitch = SwitchMaterial(this).apply {
            text = getString(R.string.visible_on_map)
            isChecked = track.visible
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_primary))
            setOnCheckedChangeListener { _, checked ->
                trackStore.setTrackVisibility(track, checked)
                loadSavedTracks()
                showTracksScreen()
            }
        }
        card.addView(visibilitySwitch)

        val actions = LinearLayout(this).apply {
            gravity = Gravity.END
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
        }
        actions.addView(actionButton(R.drawable.ic_edit, R.string.rename) {
            showRenameDialog(track)
        })
        actions.addView(actionButton(R.drawable.ic_delete, R.string.delete) {
            showDeleteDialog(track)
        })
        card.addView(actions)

        return card
    }

    private fun actionButton(iconRes: Int, labelRes: Int, action: () -> Unit): MaterialButton {
        return MaterialButton(this).apply {
            backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, android.R.color.transparent)
            text = getString(labelRes)
            textSize = 14f
            isAllCaps = false
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_primary))
            setIconResource(iconRes)
            iconTint = ContextCompat.getColorStateList(this@MainActivity, R.color.icu_text_primary)
            iconPadding = dp(6)
            minWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { action() }
        }
    }

    private fun showRenameDialog(track: RecordedTrack) {
        val input = EditText(this).apply {
            setText(track.name)
            selectAll()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(20), 0, dp(20), 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_track)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                trackStore.renameTrack(track, input.text.toString().trim())
                loadSavedTracks()
                showTracksScreen()
            }
            .show()
    }

    private fun showDeleteDialog(track: RecordedTrack) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_track_title)
            .setMessage(R.string.delete_track_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                trackStore.deleteTrack(track)
                loadSavedTracks()
                showTracksScreen()
            }
            .show()
    }

    private fun statSummaryCard(tracks: List<RecordedTrack>): View {
        return statsCard(
            title = getString(R.string.all_time),
            primary = formatDistance(tracks.sumOf { it.distanceMeters.toDouble() }.toFloat()),
            secondary = "${tracks.size} треков · ${formatDuration(tracks.sumOf { it.durationMillis })}"
        )
    }

    private fun activityStatsCard(type: TrackType, tracks: List<RecordedTrack>): View {
        val activityTracks = tracks.filter { it.type == type }
        val currentMonth = YearMonth.now()
        val monthTracks = activityTracks.filter { GpxTrackStore.monthKey(it) == currentMonth }

        val secondary = "${activityTracks.size} треков · ${getString(R.string.this_month)}: " +
            formatDistance(monthTracks.sumOf { it.distanceMeters.toDouble() }.toFloat())

        return statsCard(
            title = type.title,
            primary = formatDistance(activityTracks.sumOf { it.distanceMeters.toDouble() }.toFloat()),
            secondary = secondary,
            accentColor = type.color
        )
    }

    private fun statsCard(
        title: String,
        primary: String,
        secondary: String,
        accentColor: Int = ContextCompat.getColor(this, R.color.icu_purple_ink)
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_content_card)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }
        card.addView(TextView(this).apply {
            text = title
            setTextColor(accentColor)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(this).apply {
            text = primary
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(this).apply {
            text = secondary
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_secondary))
            textSize = 15f
        })
        return card
    }

    private fun emptyStateText(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_secondary))
            textSize = 17f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(240)
            )
        }
    }

    private fun registerRecordingReceiver() {
        if (isReceiverRegistered) return
        val filter = IntentFilter(TrackRecordingService.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(this, recordingStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        isReceiverRegistered = true
    }

    private fun unregisterRecordingReceiver() {
        if (!isReceiverRegistered) return
        unregisterReceiver(recordingStateReceiver)
        isReceiverRegistered = false
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

    private fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun formatMonthTitle(month: YearMonth): String {
        val formatter = DateTimeFormatter.ofPattern("LLLL, yyyy", Locale.forLanguageTag("ru-RU"))
        return month.format(formatter).replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.forLanguageTag("ru-RU")) else char.toString()
        }
    }

    private fun formatTrackDate(timeMillis: Long): String {
        val formatter = DateTimeFormatter
            .ofPattern("d MMMM yyyy, HH:mm", Locale.forLanguageTag("ru-RU"))
            .withZone(ZoneId.systemDefault())
        return formatter.format(Instant.ofEpochMilli(timeMillis))
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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val TRACK_STROKE_WIDTH = 8f
        private const val TIMER_INTERVAL_MS = 1_000L
    }
}
