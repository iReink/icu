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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import androidx.viewpager2.widget.ViewPager2
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

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
    private lateinit var sessionStore: SupabaseSessionStore
    private lateinit var syncMetadataStore: SyncMetadataStore
    private lateinit var supabaseClient: SupabaseApiClient
    private lateinit var syncManager: SupabaseSyncManager

    private var locationOverlay: MyLocationNewOverlay? = null
    private var addTrackSheet: BottomSheetDialog? = null
    private var pendingStartType: TrackType? = null
    private var authStatusText: TextView? = null
    private var savedTrackOverlays = mutableListOf<Polyline>()
    private var activeTrackPolyline: Polyline? = null
    private var isReceiverRegistered = false
    private var shouldFollowLocation = true
    private var wasRecording = false
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

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
                enableMyLocation(follow = shouldFollowLocation)
                continuePendingRecordingStart()
            } else {
                pendingStartType = null
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startPendingRecording()
            } else {
                pendingStartType = null
            }
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
        sessionStore = SupabaseSessionStore(this)
        syncMetadataStore = SyncMetadataStore(this)
        supabaseClient = SupabaseApiClient(sessionStore)
        syncManager = SupabaseSyncManager(trackStore, syncMetadataStore, supabaseClient)
        setContentView(R.layout.activity_main)

        bindViews()
        setupMap()
        setupDrawer()
        setupActions()
        loadSavedTracks()
        syncRecordingState()
        updateAuthHeader()
        handleAuthCallback(intent)

        if (hasLocationPermission()) {
            enableMyLocation(follow = true)
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
        if (shouldFollowLocation) {
            locationOverlay?.enableFollowLocation()
        }
        loadSavedTracks()
        syncRecordingState()
        syncTracksSilently()
    }

    override fun onPause() {
        locationOverlay?.disableMyLocation()
        unregisterRecordingReceiver()
        map.onPause()
        super.onPause()
        elapsedHandler.removeCallbacks(elapsedTicker)
    }

    override fun onDestroy() {
        backgroundExecutor.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthCallback(intent)
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
        map.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE) {
                disableLocationFollow()
            }
            false
        }
    }

    private fun setupDrawer() {
        authStatusText = navigationView.getHeaderView(0).findViewById(R.id.authStatusText)
        authStatusText?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            showAuthEntry()
        }

        navigationView.setNavigationItemSelectedListener { item ->
            drawerLayout.closeDrawer(GravityCompat.END)
            when (item.itemId) {
                R.id.menuMyTracks -> showTracksScreen()
                R.id.menuStatistics -> showStatisticsScreen()
                R.id.menuSettings -> showSettingsScreen()
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
                enableMyLocation(follow = true)
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

    private fun handleAuthCallback(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "icu" && data.host == "auth-callback") {
            Toast.makeText(this, R.string.email_confirmed_sign_in, Toast.LENGTH_LONG).show()
            if (sessionStore.current() == null) {
                showEmailDialog()
            }
        }
    }

    private fun updateAuthHeader() {
        val session = sessionStore.current()
        authStatusText?.text = if (session == null) {
            getString(R.string.sign_in_for_sync)
        } else {
            getString(R.string.signed_in_as, session.email ?: session.userId)
        }
    }

    private fun showAuthEntry() {
        val session = sessionStore.current()
        if (session != null) {
            MaterialAlertDialogBuilder(this)
                .setTitle(session.email ?: getString(R.string.app_name))
                .setItems(arrayOf(getString(R.string.sync_now), getString(R.string.sign_out))) { _, which ->
                    when (which) {
                        0 -> syncTracks(showToast = true)
                        1 -> {
                            sessionStore.clear()
                            updateAuthHeader()
                            showTracksScreenIfVisible()
                        }
                    }
                }
                .show()
            return
        }

        showEmailDialog()
    }

    private fun showEmailDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.email)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
        }
        val container = dialogInputContainer(input)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.enter_email_title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.continue_action) { _, _ ->
                val email = input.text.toString().trim()
                if (email.isNotBlank()) {
                    showPasswordDialog(email)
                }
            }
            .show()
    }

    private fun showPasswordDialog(email: String) {
        val input = EditText(this).apply {
            hint = getString(R.string.password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val container = dialogInputContainer(input)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.enter_password_title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.sign_in) { _, _ ->
                val password = input.text.toString()
                if (password.isNotBlank()) {
                    signInOrOfferSignup(email, password)
                }
            }
            .show()
    }

    private fun signInOrOfferSignup(email: String, password: String) {
        backgroundExecutor.execute {
            runCatching {
                supabaseClient.signIn(email, password)
            }.onSuccess {
                runOnUiThread {
                    updateAuthHeader()
                    syncTracks(showToast = true)
                }
            }.onFailure { error ->
                runOnUiThread {
                    if ((error as? SupabaseException)?.isInvalidCredentials() == true) {
                        showSignupOffer(email, password)
                    } else {
                        Toast.makeText(this, getString(R.string.auth_failed, error.message ?: ""), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showSignupOffer(email: String, password: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.create_account_offer_title)
            .setMessage(R.string.create_account_offer_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.create_account) { _, _ ->
                signUp(email, password)
            }
            .show()
    }

    private fun signUp(email: String, password: String) {
        backgroundExecutor.execute {
            runCatching {
                supabaseClient.signUp(email, password)
            }.onSuccess { session ->
                runOnUiThread {
                    if (session == null) {
                        Toast.makeText(this, R.string.auth_email_confirmation, Toast.LENGTH_LONG).show()
                    } else {
                        updateAuthHeader()
                        syncTracks(showToast = true)
                    }
                }
            }.onFailure { error ->
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.auth_failed, error.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun syncTracksSilently() {
        if (sessionStore.current() != null) {
            syncTracks(showToast = false)
        }
    }

    private fun syncTracks(showToast: Boolean) {
        if (sessionStore.current() == null) return
        if (showToast) {
            Toast.makeText(this, R.string.sync_started, Toast.LENGTH_SHORT).show()
        }
        backgroundExecutor.execute {
            runCatching {
                syncManager.sync()
            }.onSuccess { result ->
                runOnUiThread {
                    updateAuthHeader()
                    loadSavedTracks()
                    showTracksScreenIfVisible()
                    if (showToast) {
                        Toast.makeText(
                            this,
                            getString(R.string.sync_finished, result.uploaded, result.downloaded),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.onFailure { error ->
                runOnUiThread {
                    if (showToast) {
                        Toast.makeText(this, getString(R.string.sync_failed, error.message ?: ""), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showTracksScreenIfVisible() {
        if (sectionPanel.visibility == View.VISIBLE && sectionTitle.text == getString(R.string.my_tracks)) {
            showTracksScreen()
        }
    }

    private fun dialogInputContainer(input: EditText): FrameLayout {
        return FrameLayout(this).apply {
            setPadding(dp(20), 0, dp(20), 0)
            addView(input)
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
        val isSectionVisible = sectionPanel.visibility == View.VISIBLE

        recordingPanel.visibility = if (isRecording && !isSectionVisible) View.VISIBLE else View.GONE
        addTrackFab.visibility = if (!isRecording && !isSectionVisible) View.VISIBLE else View.GONE
        myLocationButton.visibility = if (!isSectionVisible) View.VISIBLE else View.GONE
        menuButton.visibility = if (!isRecording && !isSectionVisible) View.VISIBLE else View.GONE

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
            if (wasRecording) {
                syncTracksSilently()
            }
        }

        wasRecording = isRecording
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
            if (shouldFollowLocation) {
                map.controller.animateTo(point.toGeoPoint())
            }
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
        setSectionContentPadding(horizontalDp = 20)

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
        setSectionContentPadding(horizontalDp = 0)

        val pages = listOf(
            StatsPage(getString(R.string.all), null, ContextCompat.getColor(this, R.color.icu_purple_ink)),
            StatsPage(TrackType.WALK.title, TrackType.WALK, TrackType.WALK.color),
            StatsPage(TrackType.BIKE.title, TrackType.BIKE, TrackType.BIKE.color)
        )

        val tabs = TabLayout(this).apply {
            tabMode = TabLayout.MODE_FIXED
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val pager = ViewPager2(this).apply {
            adapter = StatsPagerAdapter(pages, tracks)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels - dp(154)).coerceAtLeast(dp(420))
            )
        }

        sectionContent.addView(tabs)
        sectionContent.addView(pager)
        TabLayoutMediator(tabs, pager) { tab, position ->
            tab.text = pages[position].title
        }.attach()
    }

    private fun showSettingsScreen() {
        showSection(getString(R.string.settings))
        setSectionContentPadding(horizontalDp = 20)
        sectionContent.addView(settingsRow())
    }

    private fun showSection(title: String) {
        sectionTitle.text = title
        sectionContent.removeAllViews()
        sectionPanel.visibility = View.VISIBLE
        addTrackFab.visibility = View.GONE
        myLocationButton.visibility = View.GONE
        menuButton.visibility = View.GONE
        recordingPanel.visibility = View.GONE
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
    }

    private fun hideSection() {
        sectionPanel.visibility = View.GONE
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        syncRecordingState()
    }

    private fun setSectionContentPadding(horizontalDp: Int) {
        sectionContent.setPadding(dp(horizontalDp), 0, dp(horizontalDp), dp(32))
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
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_track_cell)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6)
            }
        }

        card.addView(View(this).apply {
            setBackgroundColor(track.type.color)
            layoutParams = LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT)
        })

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleColumn.addView(TextView(this).apply {
            text = track.name
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        })
        titleColumn.addView(TextView(this).apply {
            text = "${track.type.title} · ${formatTrackDate(track.startedAtMillis)}"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_secondary))
            textSize = 13f
        })
        header.addView(titleColumn)

        if (!track.visible) {
            header.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_eye_off)
                imageTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.icu_text_secondary)
                contentDescription = getString(R.string.hidden_on_map)
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(40)).apply {
                    rightMargin = dp(4)
                }
                setPadding(dp(2), dp(8), dp(2), dp(8))
            })
        }

        if (!syncManager.isSynced(track)) {
            header.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_cloud_off)
                imageTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.icu_text_secondary)
                alpha = 0.3f
                contentDescription = getString(R.string.unsynced_track)
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(40)).apply {
                    rightMargin = dp(4)
                }
                setPadding(dp(2), dp(8), dp(2), dp(8))
            })
        }

        header.addView(MaterialButton(this).apply {
            backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, android.R.color.transparent)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_transparent)
            elevation = 0f
            stateListAnimator = null
            minWidth = 0
            minimumWidth = 0
            minimumHeight = dp(40)
            setPadding(0, 0, 0, 0)
            contentDescription = getString(R.string.track_actions)
            setIconResource(R.drawable.ic_kebab_vertical)
            iconTint = ContextCompat.getColorStateList(this@MainActivity, R.color.icu_purple_ink)
            iconPadding = 0
            setOnClickListener { anchor -> showTrackMenu(anchor, track) }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        })

        content.addView(header)

        content.addView(TextView(this).apply {
            text = "${formatDistance(track.distanceMeters)} · ${formatDuration(track.durationMillis)}"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_primary))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        })

        card.addView(content)
        return card
    }

    private fun showTrackMenu(anchor: View, track: RecordedTrack) {
        PopupMenu(this, anchor).apply {
            menu.add(0, TRACK_ACTION_RENAME, 0, R.string.rename)
            menu.add(
                0,
                TRACK_ACTION_VISIBILITY,
                1,
                if (track.visible) R.string.hide_from_map else R.string.show_on_map
            )
            menu.add(0, TRACK_ACTION_DELETE, 2, R.string.delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    TRACK_ACTION_RENAME -> showRenameDialog(track)
                    TRACK_ACTION_VISIBILITY -> {
                        trackStore.setTrackVisibility(track, !track.visible)
                        syncTracksSilently()
                        loadSavedTracks()
                        showTracksScreen()
                    }
                    TRACK_ACTION_DELETE -> showDeleteDialog(track)
                }
                true
            }
            show()
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
                syncTracksSilently()
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
                syncManager.markDeleted(track)
                trackStore.deleteTrack(track)
                syncTracksSilently()
                loadSavedTracks()
                showTracksScreen()
            }
            .show()
    }

    private fun settingsRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_content_card)
            setPadding(dp(16), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(TextView(this).apply {
            text = getString(R.string.high_accuracy)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(MaterialButton(this).apply {
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_transparent)
            backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, android.R.color.transparent)
            elevation = 0f
            stateListAnimator = null
            minWidth = 0
            minimumWidth = 0
            minimumHeight = dp(40)
            setPadding(0, 0, 0, 0)
            setIconResource(R.drawable.ic_info)
            iconTint = ContextCompat.getColorStateList(this@MainActivity, R.color.icu_text_secondary)
            contentDescription = getString(R.string.high_accuracy_tooltip)
            TooltipCompat.setTooltipText(this, getString(R.string.high_accuracy_tooltip))
            setOnClickListener {
                Toast.makeText(this@MainActivity, R.string.high_accuracy_tooltip, Toast.LENGTH_LONG).show()
            }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        })
        row.addView(com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
            isChecked = RecordingPreferences.isHighAccuracyEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                RecordingPreferences.setHighAccuracyEnabled(this@MainActivity, checked)
            }
        })
        return row
    }

    private fun statsPageView(page: StatsPage, allTracks: List<RecordedTrack>): View {
        val pageTracks = page.type?.let { type -> allTracks.filter { it.type == type } } ?: allTracks
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(activityStatsCard(page, pageTracks))
            addView(activityCalendarCard(page, pageTracks))
            addView(monthlyDistanceCard(page, pageTracks))
        }
    }

    private fun activityStatsCard(page: StatsPage, activityTracks: List<RecordedTrack>): View {
        val currentMonth = YearMonth.now()
        val monthTracks = activityTracks.filter { GpxTrackStore.monthKey(it) == currentMonth }
        val longestTrack = activityTracks.maxByOrNull { it.distanceMeters }

        val secondary = "${activityTracks.size} треков · ${getString(R.string.this_month)}: " +
            formatDistance(monthTracks.sumOf { it.distanceMeters.toDouble() }.toFloat()) +
            " · лучший: ${formatDistance(longestTrack?.distanceMeters ?: 0f)}"

        return statsCard(
            title = "Итого: ${page.title.lowercase(Locale.forLanguageTag("ru-RU"))}",
            primary = formatDistance(activityTracks.sumOf { it.distanceMeters.toDouble() }.toFloat()),
            secondary = secondary,
            accentColor = page.accentColor
        )
    }

    private fun activityCalendarCard(page: StatsPage, tracks: List<RecordedTrack>): View {
        val activeDates = tracks
            .map { Instant.ofEpochMilli(it.startedAtMillis).atZone(ZoneId.systemDefault()).toLocalDate() }
            .toSet()
        val dates = (27 downTo 0).map { LocalDate.now().minusDays(it.toLong()) }

        val card = contentCard()
        card.addView(cardTitle("Календарь активности", page.accentColor))
        dates.chunked(7).forEach { week ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(8)
                }
            }
            week.forEach { date ->
                row.addView(TextView(this).apply {
                    text = if (date in activeDates) "●" else "·"
                    gravity = Gravity.CENTER
                    textSize = if (date in activeDates) 24f else 28f
                    setTextColor(
                        if (date in activeDates) {
                            page.accentColor
                        } else {
                            ContextCompat.getColor(this@MainActivity, R.color.icu_sheet_divider)
                        }
                    )
                    layoutParams = LinearLayout.LayoutParams(0, dp(26), 1f)
                })
            }
            card.addView(row)
        }
        return card
    }

    private fun monthlyDistanceCard(page: StatsPage, tracks: List<RecordedTrack>): View {
        val months = (5 downTo 0).map { YearMonth.now().minusMonths(it.toLong()) }
        val distances = months.associateWith { month ->
            tracks
                .filter { GpxTrackStore.monthKey(it) == month }
                .sumOf { it.distanceMeters.toDouble() }
                .toFloat()
        }
        val maxDistance = distances.values.maxOrNull()?.coerceAtLeast(1f) ?: 1f

        val card = contentCard()
        card.addView(cardTitle("Километраж по месяцам", page.accentColor))
        months.forEach { month ->
            val distance = distances.getValue(month)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(12)
                }
            }
            row.addView(TextView(this).apply {
                text = month.format(DateTimeFormatter.ofPattern("LLL", Locale.forLanguageTag("ru-RU")))
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_secondary))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            row.addView(View(this).apply {
                setBackgroundColor(page.accentColor)
                alpha = if (distance > 0f) 1f else 0.18f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    dp(10),
                    (distance / maxDistance).coerceAtLeast(0.04f)
                )
            })
            row.addView(TextView(this).apply {
                text = formatDistance(distance)
                gravity = Gravity.END
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_secondary))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            card.addView(row)
        }
        return card
    }

    private fun statsCard(
        title: String,
        primary: String,
        secondary: String,
        accentColor: Int = ContextCompat.getColor(this, R.color.icu_purple_ink)
    ): View {
        val card = contentCard()
        card.addView(cardTitle(title, accentColor))
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

    private fun contentCard(): LinearLayout {
        return LinearLayout(this).apply {
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
    }

    private fun cardTitle(title: String, color: Int): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(color)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }
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

    private fun enableMyLocation(follow: Boolean = shouldFollowLocation) {
        if (!hasLocationPermission()) return
        shouldFollowLocation = follow

        if (locationOverlay == null) {
            locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map).also { overlay ->
                overlay.enableMyLocation()
                if (shouldFollowLocation) {
                    overlay.enableFollowLocation()
                }
                overlay.runOnFirstFix {
                    runOnUiThread {
                        overlay.myLocation?.let { location ->
                            if (shouldFollowLocation) {
                                map.controller.animateTo(location)
                                map.controller.setZoom(17.0)
                            }
                        }
                    }
                }
                map.overlays.add(overlay)
            }
        } else {
            locationOverlay?.enableMyLocation()
            if (shouldFollowLocation) {
                locationOverlay?.enableFollowLocation()
            }
        }

        map.invalidate()
    }

    private fun disableLocationFollow() {
        if (!shouldFollowLocation) return
        shouldFollowLocation = false
        locationOverlay?.disableFollowLocation()
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

    private data class StatsPage(
        val title: String,
        val type: TrackType?,
        val accentColor: Int
    )

    private inner class StatsPagerAdapter(
        private val pages: List<StatsPage>,
        private val tracks: List<RecordedTrack>
    ) : RecyclerView.Adapter<StatsPageHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StatsPageHolder {
            val container = FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            return StatsPageHolder(container)
        }

        override fun onBindViewHolder(holder: StatsPageHolder, position: Int) {
            holder.container.removeAllViews()
            holder.container.addView(statsPageView(pages[position], tracks))
        }

        override fun getItemCount(): Int = pages.size
    }

    private class StatsPageHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

    companion object {
        private const val TRACK_ACTION_RENAME = 1
        private const val TRACK_ACTION_VISIBILITY = 2
        private const val TRACK_ACTION_DELETE = 3
        private const val TRACK_STROKE_WIDTH = 8f
        private const val TIMER_INTERVAL_MS = 1_000L
    }
}
