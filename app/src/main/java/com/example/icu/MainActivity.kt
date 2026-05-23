package com.example.icu

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.text.style.BulletSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import androidx.viewpager2.widget.ViewPager2
import org.osmdroid.events.MapListener
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Overlay
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    private lateinit var map: MapView
    private lateinit var recordingPanel: View
    private lateinit var distanceText: TextView
    private lateinit var durationText: TextView
    private lateinit var measurementPanel: View
    private lateinit var measurementDistanceText: TextView
    private lateinit var measurementPointCountText: TextView
    private lateinit var measurementSaveButton: MaterialButton
    private lateinit var addTrackFab: FloatingActionButton
    private lateinit var measurementUndoFab: FloatingActionButton
    private lateinit var myLocationButton: FloatingActionButton
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var contentHost: FrameLayout
    private lateinit var reticleView: ReticleView
    private lateinit var sectionPanel: LinearLayout
    private lateinit var sectionTitle: TextView
    private lateinit var sectionContent: LinearLayout
    private lateinit var trackStore: GpxTrackStore
    private lateinit var savedPointStore: SavedPointStore
    private lateinit var sessionStore: SupabaseSessionStore
    private lateinit var syncMetadataStore: SyncMetadataStore
    private lateinit var supabaseClient: SupabaseApiClient
    private lateinit var syncManager: SupabaseSyncManager
    private lateinit var appLocationManager: LocationManager
    private lateinit var liveLocationUploader: LiveLocationUploader

    private var locationOverlay: MyLocationNewOverlay? = null
    private var addTrackSheet: BottomSheetDialog? = null
    private var pendingStartType: TrackType? = null
    private var hasPendingLocationBroadcastStart = false
    private var pendingLocationBroadcastDurationMs: Long? = null
    private var pendingInviteToken: String? = null
    private var highlightedFriendshipId: String? = null
    private var savedTrackOverlays = mutableListOf<Overlay>()
    private var allSavedTracks: List<RecordedTrack> = emptyList()
    private var hasLoadedSavedTracks = false
    private var visibleSavedTracks: List<RecordedTrack> = emptyList()
    private var focusedTrackFileName: String? = null
    private var savedPointOverlays = mutableListOf<Overlay>()
    private var friendLocationOverlays = mutableListOf<Overlay>()
    private var destinationMarker: Marker? = null
    private var destinationDimOverlay: ReticleDimOverlay? = null
    private var activeTrackPolyline: Polyline? = null
    private var measurementPolyline: Polyline? = null
    private val measurementPoints = mutableListOf<TrackPoint>()
    private val measurementGeoPoints = mutableListOf<GeoPoint>()
    private var isMeasurementMode = false
    private var fixedMeasurementDistanceMeters = 0f
    private var isReceiverRegistered = false
    private var shouldFollowLocation = true
    private var wasRecording = false
    private var pendingSavedTrackFileName: String? = null
    private var currentSection: Section = Section.NONE
    private var currentAuthStep: AuthStep = AuthStep.NONE
    private var lastAuthEmail: String = ""
    private var authRootView: LinearLayout? = null
    private var lastForegroundLiveUploadMillis = 0L
    private var lastKnownUserLocation: Location? = null
    private var cachedUserProfile: UserProfile? = null
    private var cachedFriends: List<FriendProfile> = emptyList()
    private var cachedFriendLocations: Map<String, List<LocationSharePoint>> = emptyMap()
    private var lastProfileRefreshMillis = 0L
    private var friendLocationRefreshInFlight = false
    private var profileRefreshInFlight = false
    private var friendTooltip: PopupWindow? = null
    private var infoTooltip: PopupWindow? = null
    private var savedTracksLoadRequest = 0
    private var selectedTrackListTab = 0
    private var trackSearchQuery = ""
    private var pointSearchQuery = ""
    private var skippedTrackRefreshes = 0
    private var skippedPointRefreshes = 0
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    private val foregroundLocationListener = LocationListener { location ->
        lastKnownUserLocation = location
        uploadForegroundLiveLocation(location)
    }

    private val elapsedHandler = Handler(Looper.getMainLooper())
    private val elapsedTicker = object : Runnable {
        override fun run() {
            updateRecordingPanelTime()
            elapsedHandler.postDelayed(this, TIMER_INTERVAL_MS)
        }
    }

    private val friendLocationRefreshTicker = object : Runnable {
        override fun run() {
            refreshFriendLocationsOnMap()
            elapsedHandler.postDelayed(this, FRIEND_LOCATION_REFRESH_MS)
        }
    }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val isGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (isGranted) {
                enableMyLocation(follow = shouldFollowLocation)
                when {
                    pendingStartType != null -> continuePendingRecordingStart()
                    hasPendingLocationBroadcastStart -> continuePendingLocationBroadcastStart()
                }
            } else {
                pendingStartType = null
                hasPendingLocationBroadcastStart = false
                pendingLocationBroadcastDurationMs = null
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                when {
                    pendingStartType != null -> startPendingRecording()
                    hasPendingLocationBroadcastStart -> startPendingLocationBroadcast()
                }
            } else {
                pendingStartType = null
                hasPendingLocationBroadcastStart = false
                pendingLocationBroadcastDurationMs = null
            }
        }

    private val recordingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            pendingSavedTrackFileName = intent?.getStringExtra(TrackRecordingService.EXTRA_SAVED_TRACK_FILE_NAME)
            syncRecordingState()
            if (intent?.action == LocationBroadcastService.ACTION_STATE_CHANGED && currentSection == Section.PROFILE) {
                showProfileScreen()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_ICU)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = packageName
        trackStore = GpxTrackStore(this)
        savedPointStore = SavedPointStore(this)
        sessionStore = SupabaseSessionStore(this)
        syncMetadataStore = SyncMetadataStore(this)
        supabaseClient = SupabaseApiClient(sessionStore)
        syncManager = SupabaseSyncManager(trackStore, savedPointStore, syncMetadataStore, supabaseClient)
        appLocationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        liveLocationUploader = LiveLocationUploader(this)
        setContentView(R.layout.activity_main)
        showStartupSplash()

        bindViews()
        setupImeInsets()
        setupMap()
        setupBottomNavigation()
        setupActions()
        syncRecordingState()
        updateAuthHeader()
        handleAuthCallback(intent)
        handleFriendInvite(intent)

        if (hasLocationPermission()) {
            enableMyLocation(follow = true)
        } else {
            requestLocationPermission()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    isMeasurementMode -> exitMeasurementMode()
                    sectionPanel.visibility == View.VISIBLE -> handleSectionBack()
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
        loadSavedTracksAsync()
        loadSavedPointsOnMap()
        syncRecordingState()
        findViewById<View>(R.id.mainContent).postDelayed({
            syncTracksSilently()
            requestForegroundLiveLocationUpdates()
            refreshFriendLocationsOnMap()
        }, STARTUP_DEFER_MS)
        elapsedHandler.removeCallbacks(friendLocationRefreshTicker)
        elapsedHandler.postDelayed(friendLocationRefreshTicker, FRIEND_LOCATION_REFRESH_MS)
    }

    override fun onPause() {
        locationOverlay?.disableMyLocation()
        stopForegroundLiveLocationUpdates()
        unregisterRecordingReceiver()
        map.onPause()
        super.onPause()
        elapsedHandler.removeCallbacks(elapsedTicker)
        elapsedHandler.removeCallbacks(friendLocationRefreshTicker)
        friendTooltip?.dismiss()
        infoTooltip?.dismiss()
    }

    override fun onDestroy() {
        backgroundExecutor.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthCallback(intent)
        handleFriendInvite(intent)
    }

    private fun bindViews() {
        map = findViewById(R.id.map)
        contentHost = findViewById(R.id.contentHost)
        recordingPanel = findViewById(R.id.recordingPanel)
        distanceText = findViewById(R.id.distanceText)
        durationText = findViewById(R.id.durationText)
        measurementPanel = findViewById(R.id.measurementPanel)
        measurementDistanceText = findViewById(R.id.measurementDistanceText)
        measurementPointCountText = findViewById(R.id.measurementPointCountText)
        measurementSaveButton = findViewById(R.id.measurementSaveButton)
        addTrackFab = findViewById(R.id.addTrackFab)
        measurementUndoFab = findViewById(R.id.measurementUndoFab)
        myLocationButton = findViewById(R.id.myLocationButton)
        bottomNavigation = findViewById(R.id.bottomNavigation)
        reticleView = findViewById(R.id.reticleView)
        sectionPanel = findViewById(R.id.sectionPanel)
        sectionTitle = findViewById(R.id.sectionTitle)
        sectionContent = findViewById(R.id.sectionContent)
    }

    private fun showStartupSplash() {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.icu_launcher_background))
            isClickable = true
            isFocusable = true
        }

        val background = ImageView(this).apply {
            setImageResource(R.drawable.icu_splash_background)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.96f
            scaleX = 1.03f
            scaleY = 1.03f
        }
        overlay.addView(background, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        addContentView(overlay, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        background.animate()
            .scaleX(1.07f)
            .scaleY(1.07f)
            .setDuration(SPLASH_DURATION_MS)
            .start()
        overlay.postDelayed({
            overlay.animate()
                .alpha(0f)
                .setDuration(360L)
                .withEndAction {
                    (overlay.parent as? ViewGroup)?.removeView(overlay)
                }
                .start()
        }, SPLASH_VISIBLE_MS)
    }

    private fun setupImeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(bottomNavigation) { view, insets ->
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                dp(8) + navInsets.bottom
            )
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(sectionPanel) { _, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val isImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            authRootView?.setPadding(
                0,
                0,
                0,
                if (isImeVisible) imeInsets.bottom else 0
            )
            insets
        }
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
        map.overlays.add(MapEventsOverlay(this, object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(point: GeoPoint): Boolean {
                if (isMeasurementMode) {
                    addMeasurementPoint()
                    return true
                }
                val handledTrackTap = handleSavedTrackTap(point)
                if (!handledTrackTap) {
                    clearTrackFocus()
                }
                return handledTrackTap
            }

            override fun longPressHelper(point: GeoPoint): Boolean {
                if (isMeasurementMode) return true
                showDestinationPreviewSheet(reticleTargetPoint())
                return true
            }
        }))
        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                if (isMeasurementMode) updateMeasurementPreview()
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                if (isMeasurementMode) updateMeasurementPreview()
                return false
            }
        })
    }

    private fun setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navMap -> {
                    hideSection()
                    true
                }
                R.id.navTracks -> {
                    clearTrackFocus()
                    showTracksScreen()
                    true
                }
                R.id.navPoints -> {
                    clearTrackFocus()
                    showPointsScreen()
                    true
                }
                R.id.navProfile -> {
                    clearTrackFocus()
                    showProfileScreen()
                    true
                }
                R.id.navSettings -> {
                    clearTrackFocus()
                    showToolsScreen()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupActions() {
        myLocationButton.setOnClickListener {
            if (hasLocationPermission()) {
                enableMyLocation(follow = true)
            } else {
                requestLocationPermission()
            }
        }

        addTrackFab.setOnClickListener {
            if (isMeasurementMode) {
                addMeasurementPoint()
            } else {
                showAddTrackSheet()
            }
        }

        measurementUndoFab.setOnClickListener {
            undoMeasurementPoint()
        }

        findViewById<MaterialButton>(R.id.measurementBackButton).setOnClickListener {
            exitMeasurementMode()
        }

        measurementSaveButton.setOnClickListener {
            showSaveMeasurementDialog()
        }

        findViewById<MaterialButton>(R.id.finishRecordingButton).setOnClickListener {
            stopRecordingService()
        }

        findViewById<MaterialButton>(R.id.closeSectionButton).setOnClickListener {
            handleSectionBack()
        }
    }

    private fun handleSectionBack() {
        if (currentSection == Section.AUTH && currentAuthStep == AuthStep.PASSWORD) {
            showAuthEmailScreen()
            return
        }
        if (currentSection == Section.STATISTICS) {
            hideKeyboard()
            bottomNavigation.selectedItemId = R.id.navProfile
            showProfileScreen()
            return
        }
        hideKeyboard()
        hideSection()
        bottomNavigation.selectedItemId = R.id.navMap
    }

    private fun handleAuthCallback(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "icu" && data.host == "auth-callback") {
            showAuthSuccessScreen()
        }
    }

    private fun handleFriendInvite(intent: Intent?) {
        val data = intent?.data ?: return
        val isAppInvite = data.scheme == "icu" && data.host == "friend-invite"
        val isHttpsInvite = data.scheme == "https" &&
            data.host == "jjinirtbtgkyesewvyux.supabase.co" &&
            data.path == "/functions/v1/friend-invite"
        if (!isAppInvite && !isHttpsInvite) return
        val token = data.getQueryParameter("token") ?: return
        pendingInviteToken = token
        if (sessionStore.current() == null) {
            bottomNavigation.selectedItemId = R.id.navProfile
            showSnackbar(getString(R.string.sign_in_to_accept_friend), isLong = true)
            showAuthEntry()
        } else {
            acceptFriendInvite(token)
        }
    }

    private fun updateAuthHeader() {
        if (currentSection == Section.PROFILE) showProfileScreen()
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

        showAuthEmailScreen()
    }

    private fun showAuthEmailScreen() {
        currentSection = Section.AUTH
        currentAuthStep = AuthStep.EMAIL
        showSection(getString(R.string.sign_in))

        val emailInput = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(lastAuthEmail)
            setSelection(text?.length ?: 0)
            setSingleLine(true)
        }

        setAuthScreenContent(
            bodyViews = listOf(
                authInputBlock(
                    title = getString(R.string.enter_email_title),
                    input = emailInput,
                    hint = getString(R.string.email)
                )
            ),
            actions = authBottomActions(
                primaryText = getString(R.string.continue_action),
                onPrimary = {
                    val email = emailInput.text?.toString()?.trim().orEmpty()
                    if (email.isBlank()) return@authBottomActions
                    lastAuthEmail = email
                    checkEmailAndShowPassword(email)
                },
                secondaryText = getString(R.string.close),
                onSecondary = ::hideSection
            )
        )
    }

    private fun checkEmailAndShowPassword(email: String) {
        backgroundExecutor.execute {
            runCatching {
                supabaseClient.emailExists(email)
            }.onSuccess { exists ->
                runOnUiThread {
                    showAuthPasswordScreen(email, exists)
                }
            }.onFailure { error ->
                runOnUiThread {
                    showSnackbar(getString(R.string.auth_failed, userMessage(error)), isLong = true)
                }
            }
        }
    }

    private fun showAuthPasswordScreen(email: String, emailExists: Boolean) {
        currentSection = Section.AUTH
        currentAuthStep = AuthStep.PASSWORD
        showSection(if (emailExists) getString(R.string.sign_in) else getString(R.string.create_account))

        val emailText = TextView(this).apply {
            text = email
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_secondary))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(18)
            }
        }

        val passwordInput = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            setSingleLine(true)
        }

        val bodyViews = mutableListOf<View>(emailText)
        bodyViews.add(
            authInputBlock(
                title = if (emailExists) getString(R.string.enter_password_title) else getString(R.string.create_password_title),
                input = passwordInput,
                hint = getString(R.string.password),
                isPassword = true
            )
        )

        if (emailExists) {
            bodyViews.add(TextView(this).apply {
                text = getString(R.string.password_recovery_available)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_secondary))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(12)
                }
            })
        }

        setAuthScreenContent(
            bodyViews = bodyViews,
            actions = authBottomActions(
                primaryText = if (emailExists) getString(R.string.sign_in) else getString(R.string.create_account),
                onPrimary = {
                    val password = passwordInput.text?.toString().orEmpty()
                    if (password.isBlank()) return@authBottomActions
                    if (emailExists) {
                        signIn(email, password)
                    } else {
                        signUp(email, password)
                    }
                },
                secondaryText = getString(R.string.close),
                onSecondary = ::hideSection
            )
        )
    }

    private fun signIn(email: String, password: String) {
        backgroundExecutor.execute {
            runCatching {
                supabaseClient.signIn(email, password)
            }.onSuccess {
                runOnUiThread {
                    sessionStore.clearPendingEmail()
                    updateAuthHeader()
                    acceptPendingInviteIfNeeded()
                    hideKeyboard()
                    hideSection()
                    bottomNavigation.selectedItemId = R.id.navMap
                    requestForegroundLiveLocationUpdates()
                    refreshFriendLocationsOnMap()
                    syncTracks(showToast = true)
                }
            }.onFailure { error ->
                runOnUiThread {
                    showSnackbar(getString(R.string.auth_failed, userMessage(error)), isLong = true)
                }
            }
        }
    }

    private fun signUp(email: String, password: String) {
        backgroundExecutor.execute {
            runCatching {
                supabaseClient.signUp(email, password)
            }.onSuccess { session ->
                runOnUiThread {
                    if (session == null) {
                        sessionStore.savePendingEmail(email)
                        showAuthCheckEmailScreen(email)
                    } else {
                        sessionStore.clearPendingEmail()
                        updateAuthHeader()
                        acceptPendingInviteIfNeeded()
                        hideKeyboard()
                        hideSection()
                        bottomNavigation.selectedItemId = R.id.navMap
                        requestForegroundLiveLocationUpdates()
                        refreshFriendLocationsOnMap()
                        syncTracks(showToast = true)
                    }
                }
            }.onFailure { error ->
                runOnUiThread {
                    showSnackbar(getString(R.string.auth_failed, userMessage(error)), isLong = true)
                }
            }
        }
    }

    private fun showAuthCheckEmailScreen(email: String) {
        currentSection = Section.AUTH
        currentAuthStep = AuthStep.MESSAGE
        showSection(getString(R.string.create_account))

        val title = TextView(this).apply {
            text = getString(R.string.check_email_title)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        }
        val message = TextView(this).apply {
            text = getString(R.string.check_email_message, email)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_secondary))
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        }

        setAuthScreenContent(
            bodyViews = listOf(title, message),
            actions = authBottomActions(
                primaryText = getString(R.string.ok),
                onPrimary = ::hideSection,
                secondaryText = getString(R.string.close),
                onSecondary = ::hideSection
            )
        )
    }

    private fun showAuthSuccessScreen() {
        val pendingEmail = sessionStore.pendingEmail()
        currentSection = Section.AUTH
        currentAuthStep = AuthStep.MESSAGE
        showSection(getString(R.string.create_account))

        val title = TextView(this).apply {
            text = getString(R.string.registration_success_title)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        }
        val message = TextView(this).apply {
            text = getString(R.string.registration_success_message)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_secondary))
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        }
        setAuthScreenContent(
            bodyViews = listOf(title, message),
            actions = authBottomActions(
                primaryText = getString(R.string.ok),
                onPrimary = {
                    if (pendingEmail != null) {
                        showAuthPasswordScreen(pendingEmail, emailExists = true)
                    } else {
                        hideSection()
                        showAuthEntry()
                    }
                },
                secondaryText = null,
                onSecondary = null
            )
        )
    }

    private fun authInputBlock(
        title: String,
        input: TextInputEditText,
        hint: String,
        isPassword: Boolean = false
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextInputLayout(this@MainActivity).apply {
                this.hint = hint
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
                if (isPassword) {
                    endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(16)
                }
                addView(input)
                if (isPassword) {
                    input.transformationMethod = PasswordTransformationMethod.getInstance()
                    input.setSelection(input.text?.length ?: 0)
                }
            })
        }
    }

    private fun setAuthScreenContent(bodyViews: List<View>, actions: View) {
        setSectionContentPadding(horizontalDp = 20)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        authRootView = root

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            bodyViews.forEach { addView(it) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        root.addView(body)
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        })
        root.addView(actions)
        sectionContent.addView(root)
        sectionContent.post {
            root.minimumHeight = sectionContent.height
            ViewCompat.requestApplyInsets(sectionPanel)
        }
    }

    private fun authBottomActions(
        primaryText: String,
        onPrimary: () -> Unit,
        secondaryText: String?,
        onSecondary: (() -> Unit)?
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(280)
                bottomMargin = dp(24)
            }

            if (secondaryText != null && onSecondary != null) {
                addView(MaterialButton(this@MainActivity).apply {
                    text = secondaryText
                    isAllCaps = false
                    backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, android.R.color.transparent)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_purple_ink))
                    elevation = 0f
                    stateListAnimator = null
                    setOnClickListener { onSecondary() }
                    layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                        rightMargin = dp(8)
                    }
                })
            } else {
                addView(View(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }

            addView(MaterialButton(this@MainActivity).apply {
                text = primaryText
                isAllCaps = false
                setOnClickListener { onPrimary() }
                layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                    leftMargin = dp(8)
                }
            })
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
            showSnackbar(getString(R.string.sync_started))
        }
        backgroundExecutor.execute {
            runCatching {
                syncManager.sync()
            }.onSuccess { result ->
                runOnUiThread {
                    updateAuthHeader()
                    loadSavedTracksAsync()
                    loadSavedPointsOnMap()
                    showTracksScreenIfVisible()
                    showPointsScreenIfVisible()
                    if (showToast) {
                        showSnackbar(
                            getString(R.string.sync_finished, result.uploaded, result.downloaded),
                            isLong = true
                        )
                    }
                }
            }.onFailure { error ->
                runOnUiThread {
                    if (showToast) {
                        showSnackbar(getString(R.string.sync_failed, userMessage(error)), isLong = true)
                    }
                }
            }
        }
    }

    private fun showTracksScreenIfVisible() {
        if (sectionPanel.visibility == View.VISIBLE && currentSection == Section.TRACKS) {
            if (skippedTrackRefreshes > 0) {
                skippedTrackRefreshes--
                return
            }
            if (isSearchInputFocused()) return
            showTracksScreen()
        }
    }

    private fun showPointsScreenIfVisible() {
        if (sectionPanel.visibility == View.VISIBLE && currentSection == Section.POINTS) {
            if (skippedPointRefreshes > 0) {
                skippedPointRefreshes--
                return
            }
            if (isSearchInputFocused()) return
            showPointsScreen()
        }
    }

    private fun isSearchInputFocused(): Boolean {
        return (currentFocus as? TextInputEditText)?.tag == SEARCH_INPUT_TAG
    }

    private fun showSnackbar(message: String, isLong: Boolean = false) {
        showSnackbar(
            message = message,
            duration = if (isLong) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
        )
    }

    private fun showSnackbar(
        message: String,
        duration: Int,
        actionText: String? = null,
        action: (() -> Unit)? = null
    ) {
        val root = findViewById<View>(R.id.mainContent)
        Snackbar.make(
            root,
            message,
            duration
        ).apply {
            anchorView = when {
                sectionPanel.visibility == View.VISIBLE -> null
                addTrackFab.visibility == View.VISIBLE -> addTrackFab
                else -> null
            }
            if (actionText != null && action != null) {
                setAction(actionText) { action() }
            }
        }.show()
    }

    private fun showTrackSavedSnackbar(track: RecordedTrack) {
        focusTrack(track)
        elapsedHandler.postDelayed({
            if (focusedTrackFileName == track.file.name) clearTrackFocus()
        }, AUTO_TRACK_FOCUS_MS)
        showSnackbar(
            message = getString(R.string.track_saved_visible),
            duration = TRACK_SAVED_SNACKBAR_MS,
            actionText = getString(R.string.hide_action)
        ) {
            clearTrackFocus()
            val updated = trackStore.setTrackVisibility(track, false)
            replaceCachedTrack(updated)
            applySavedTrackOverlays(savedTracksForMap())
            skippedTrackRefreshes = 2
            syncTracksSilently()
            showTracksScreenIfVisible()
        }
    }

    private fun showSavedTrackSnackbarIfNeeded() {
        val savedTrackFileName = pendingSavedTrackFileName ?: return
        pendingSavedTrackFileName = null
        allSavedTracks
            .firstOrNull { track -> track.file.name == savedTrackFileName }
            ?.let { savedTrack -> showTrackSavedSnackbar(savedTrack) }
    }

    private fun hideKeyboard() {
        val view = currentFocus ?: findViewById<View>(R.id.mainContent)
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    private fun userMessage(error: Throwable): String {
        return when {
            error is SupabaseException && error.isRateLimited() -> getString(R.string.rate_limit_message)
            error is SupabaseException -> error.readableMessage()
            else -> error.message ?: getString(R.string.unknown_error)
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

        content.findViewById<MaterialButton>(R.id.cancelAddTrackButton).setOnClickListener {
            sheet.dismiss()
        }
        content.findViewById<MaterialButton>(R.id.startTrackButton).setOnClickListener {
            val selectedType = when (content.findViewById<RadioGroup>(R.id.addTrackTypeGroup).checkedRadioButtonId) {
                R.id.bikeTrackRadio -> TrackType.BIKE
                else -> TrackType.WALK
            }
            sheet.dismiss()
            requestStartRecording(selectedType)
        }
        addTrackSheet = sheet
        sheet.show()
    }

    private fun requestStartRecording(type: TrackType) {
        if (TrackRecordingService.currentState.isRecording) {
            showSnackbar(getString(R.string.recording_already_started))
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

        clearTrackFocus()
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

        recordingPanel.visibility = if (isRecording && !isSectionVisible && !isMeasurementMode) View.VISIBLE else View.GONE
        measurementPanel.visibility = if (isMeasurementMode) View.VISIBLE else View.GONE
        bottomNavigation.visibility = if (isMeasurementMode) View.GONE else View.VISIBLE
        addTrackFab.visibility = if (!isRecording && !isSectionVisible || isMeasurementMode) View.VISIBLE else View.GONE
        myLocationButton.visibility = if (!isSectionVisible && !isMeasurementMode) View.VISIBLE else View.GONE
        reticleView.visibility = if (!isSectionVisible || isMeasurementMode) View.VISIBLE else View.GONE
        measurementUndoFab.visibility = if (isMeasurementMode && measurementPoints.isNotEmpty()) View.VISIBLE else View.GONE

        if (isRecording) {
            updateRecordingPanelTime()
            drawActiveTrack(state)
            elapsedHandler.removeCallbacks(elapsedTicker)
            elapsedHandler.post(elapsedTicker)
        } else {
            elapsedHandler.removeCallbacks(elapsedTicker)
            activeTrackPolyline?.let { map.overlays.remove(it) }
            activeTrackPolyline = null
            if (wasRecording) {
                loadSavedTracksAsync()
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

    private fun loadSavedTracksAsync() {
        val request = ++savedTracksLoadRequest
        backgroundExecutor.execute {
            val tracks = trackStore.loadTracks()
            runOnUiThread {
                if (request == savedTracksLoadRequest) {
                    allSavedTracks = tracks
                    hasLoadedSavedTracks = true
                    applySavedTrackOverlays(savedTracksForMap())
                    showSavedTrackSnackbarIfNeeded()
                    showTracksScreenIfVisible()
                }
            }
        }
    }

    private fun applySavedTrackOverlays(tracks: List<RecordedTrack>) {
        savedTrackOverlays.forEach { map.overlays.remove(it) }
        savedTrackOverlays.clear()
        visibleSavedTracks = tracks.filter { it.visible }
        val focusedTrack = tracks.firstOrNull { it.file.name == focusedTrackFileName }
        val regularTracks = tracks.filterNot { it.file.name == focusedTrackFileName }

        regularTracks.forEach { track ->
            addSavedTrackOverlay(track, isFocused = false, isDimmed = focusedTrack != null)
        }
        bringSavedPointOverlaysToFront()
        focusedTrack?.let { track ->
            addSavedTrackOverlay(track, isFocused = true, isDimmed = false)
        }
        map.invalidate()
    }

    private fun savedTracksForMap(): List<RecordedTrack> {
        val visibleTracks = allSavedTracks.filter { it.visible }
        val focusedHiddenTrack = focusedTrackFileName?.let { fileName ->
            allSavedTracks.firstOrNull { track -> track.file.name == fileName && !track.visible }
        }
        return if (focusedHiddenTrack != null) visibleTracks + focusedHiddenTrack else visibleTracks
    }

    private fun bringSavedPointOverlaysToFront() {
        if (savedPointOverlays.isEmpty()) return
        savedPointOverlays.forEach { overlay ->
            map.overlays.remove(overlay)
            map.overlays.add(overlay)
        }
    }

    private fun addSavedTrackOverlay(track: RecordedTrack, isFocused: Boolean, isDimmed: Boolean) {
        if (track.points.size == 1) {
            val marker = createSinglePointTrackMarker(track, isFocused, isDimmed)
            savedTrackOverlays.add(marker)
            map.overlays.add(marker)
        } else {
            if (isFocused) {
                val halo = createFocusedTrackHalo(track)
                halo.setPoints(track.points.map { it.toGeoPoint() })
                savedTrackOverlays.add(halo)
                map.overlays.add(halo)
            }
            val polyline = createSavedTrackPolyline(track, isFocused, isDimmed)
            polyline.setPoints(track.points.map { it.toGeoPoint() })
            savedTrackOverlays.add(polyline)
            map.overlays.add(polyline)
        }
    }

    private fun createTrackPolyline(type: TrackType): Polyline {
        return Polyline(map).apply {
            outlinePaint.color = type.color
            outlinePaint.strokeWidth = TRACK_STROKE_WIDTH
            outlinePaint.isAntiAlias = true
            setOnClickListener { _, _, _ ->
                if (isMeasurementMode) addMeasurementPoint()
                true
            }
        }
    }

    private fun createSavedTrackPolyline(track: RecordedTrack, isFocused: Boolean, isDimmed: Boolean): Polyline {
        return createTrackPolyline(track.type).apply {
            outlinePaint.strokeWidth = if (isFocused) TRACK_FOCUS_STROKE_WIDTH else TRACK_STROKE_WIDTH
            outlinePaint.alpha = if (isDimmed) TRACK_DIM_ALPHA else 255
            setOnClickListener { _, _, eventPos ->
                if (isMeasurementMode) {
                    addMeasurementPoint()
                } else {
                    handleSavedTrackTap(eventPos)
                }
                true
            }
        }
    }

    private fun createFocusedTrackHalo(track: RecordedTrack): Polyline {
        return Polyline(map).apply {
            outlinePaint.color = Color.WHITE
            outlinePaint.strokeWidth = TRACK_FOCUS_HALO_WIDTH
            outlinePaint.alpha = 220
            outlinePaint.isAntiAlias = true
            setOnClickListener { _, _, eventPos ->
                if (isMeasurementMode) {
                    addMeasurementPoint()
                } else {
                    handleSavedTrackTap(eventPos)
                }
                true
            }
        }
    }

    private fun createSinglePointTrackMarker(track: RecordedTrack, isFocused: Boolean, isDimmed: Boolean): Marker {
        return Marker(map).apply {
            position = track.points.first().toGeoPoint()
            icon = BitmapDrawable(resources, createTrackPointIcon(track.type.color, isFocused, isDimmed))
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setOnMarkerClickListener { marker, _ ->
                if (isMeasurementMode) {
                    addMeasurementPoint()
                } else {
                    handleSavedTrackTap(marker.position)
                }
                true
            }
        }
    }

    private fun handleSavedTrackTap(point: GeoPoint): Boolean {
        if (sectionPanel.visibility == View.VISIBLE || visibleSavedTracks.isEmpty()) return false

        val hits = visibleSavedTracks
            .mapNotNull { track ->
                distanceToTrackPixels(track, point)
                    ?.takeIf { distance -> distance <= dp(24).toDouble() }
                    ?.let { distance -> TrackHit(track, distance) }
            }
            .sortedBy { it.distancePx }
            .map { it.track }

        return when (hits.size) {
            0 -> {
                clearTrackFocus()
                false
            }
            1 -> {
                focusTrack(hits.first(), tooltipPoint = point, fitToScreen = false)
                true
            }
            else -> {
                showTrackSelectionDialog(hits, point)
                true
            }
        }
    }

    private fun distanceToTrackPixels(track: RecordedTrack, tapPoint: GeoPoint): Double? {
        val points = track.points
        if (points.isEmpty()) return null

        val tap = pointOnScreen(tapPoint)
        if (points.size == 1) {
            return distancePixels(tap, pointOnScreen(points.first().toGeoPoint()))
        }

        var minDistance = Double.MAX_VALUE
        points.zipWithNext { start, end ->
            val distance = distanceToSegmentPixels(
                tap = tap,
                start = pointOnScreen(start.toGeoPoint()),
                end = pointOnScreen(end.toGeoPoint())
            )
            minDistance = min(minDistance, distance)
        }
        return minDistance
    }

    private fun pointOnScreen(point: GeoPoint): Point {
        return Point().also { map.projection.toPixels(point, it) }
    }

    private fun distanceToSegmentPixels(tap: Point, start: Point, end: Point): Double {
        val dx = (end.x - start.x).toDouble()
        val dy = (end.y - start.y).toDouble()
        if (dx == 0.0 && dy == 0.0) return distancePixels(tap, start)

        val t = (((tap.x - start.x) * dx + (tap.y - start.y) * dy) / (dx * dx + dy * dy))
            .coerceIn(0.0, 1.0)
        val projectionX = start.x + t * dx
        val projectionY = start.y + t * dy
        return sqrt((tap.x - projectionX) * (tap.x - projectionX) + (tap.y - projectionY) * (tap.y - projectionY))
    }

    private fun distancePixels(first: Point, second: Point): Double {
        val dx = (first.x - second.x).toDouble()
        val dy = (first.y - second.y).toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    private fun showTrackSelectionDialog(tracks: List<RecordedTrack>, tapPoint: GeoPoint) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(4))
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Выберите маршрут")
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .create()

        tracks.forEach { track ->
            content.addView(trackSelectionCard(track) {
                dialog.dismiss()
                focusTrack(track, tooltipPoint = tapPoint, fitToScreen = false)
            })
        }

        dialog.show()
    }

    private fun focusTrack(track: RecordedTrack, tooltipPoint: GeoPoint? = null, fitToScreen: Boolean = true) {
        focusedTrackFileName = track.file.name
        applySavedTrackOverlays(savedTracksForMap())
        if (fitToScreen) {
            fitTrackInMap(track)
        }
        val point = tooltipPoint ?: trackTooltipPoint(track)
        val delay = if (fitToScreen) TRACK_FOCUS_TOOLTIP_DELAY_MS else 0L
        elapsedHandler.postDelayed({
            showTrackTooltip(track, point)
        }, delay)
    }

    private fun clearTrackFocus() {
        if (focusedTrackFileName == null && infoTooltip == null) return
        focusedTrackFileName = null
        infoTooltip?.dismiss()
        infoTooltip = null
        applySavedTrackOverlays(savedTracksForMap())
    }

    private fun fitTrackInMap(track: RecordedTrack) {
        if (track.points.isEmpty()) return
        if (track.points.size == 1) {
            map.controller.animateTo(track.points.first().toGeoPoint())
            map.controller.setZoom(max(map.zoomLevelDouble, 16.0))
            return
        }
        map.zoomToBoundingBox(track.boundingBox(), true, dp(TRACK_FOCUS_VIEW_PADDING_DP))
    }

    private fun trackTooltipPoint(track: RecordedTrack): GeoPoint {
        return track.points.getOrNull(track.points.size / 2)?.toGeoPoint()
            ?: track.points.firstOrNull()?.toGeoPoint()
            ?: GeoPoint(map.mapCenter.latitude, map.mapCenter.longitude)
    }

    private fun RecordedTrack.boundingBox(): BoundingBox {
        val north = points.maxOf { it.latitude }
        val south = points.minOf { it.latitude }
        val east = points.maxOf { it.longitude }
        val west = points.minOf { it.longitude }
        return BoundingBox(north, east, south, west)
    }

    private fun trackSelectionCard(track: RecordedTrack, onClick: () -> Unit): View {
        return MaterialCardView(this).apply {
            radius = dp(8).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(Color.WHITE)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))

                addView(View(this@MainActivity).apply {
                    setBackgroundColor(track.type.color)
                    layoutParams = LinearLayout.LayoutParams(dp(4), dp(48)).apply {
                        rightMargin = dp(12)
                    }
                })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@MainActivity).apply {
                        text = track.name
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
                        textSize = 16f
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = "${formatDistance(track.distanceMeters)} · ${formatTrackDate(track.startedAtMillis)}"
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_secondary))
                        textSize = 13f
                    })
                })
            })
        }
    }

    private fun showTrackTooltip(track: RecordedTrack, geoPoint: GeoPoint) {
        infoTooltip?.dismiss()

        val title = TextView(this).apply {
            text = track.name
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        val body = TextView(this).apply {
            text = "${formatTrackDate(track.startedAtMillis)}\n${formatDistance(track.distanceMeters)} · ${formatDuration(track.durationMillis)}"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_secondary))
            textSize = 14f
            setLineSpacing(dp(3).toFloat(), 1.0f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.rgb(243, 237, 247))
                cornerRadius = dp(16).toFloat()
            }
            elevation = dp(6).toFloat()
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(title)
            addView(body, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
        }

        val width = (resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(320))
        val popup = PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
        }
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val pointOnMap = Point()
        map.projection.toPixels(geoPoint, pointOnMap)
        val mapOnScreen = IntArray(2)
        map.getLocationOnScreen(mapOnScreen)
        val minX = mapOnScreen[0] + dp(12)
        val maxX = mapOnScreen[0] + map.width - width - dp(12)
        val minY = mapOnScreen[1] + dp(12)
        val x = (mapOnScreen[0] + pointOnMap.x - width / 2).coerceIn(minX, maxX.coerceAtLeast(minX))
        val y = (mapOnScreen[1] + pointOnMap.y - content.measuredHeight - dp(14)).coerceAtLeast(minY)
        popup.showAtLocation(map, Gravity.NO_GRAVITY, x, y)
        infoTooltip = popup
    }

    private fun createTrackPointIcon(color: Int, isFocused: Boolean = false, isDimmed: Boolean = false): Bitmap {
        val size = dp(if (isFocused) 26 else 18).coerceAtLeast(18)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val center = size / 2f
        paint.style = Paint.Style.FILL
        paint.alpha = if (isDimmed) TRACK_DIM_ALPHA else 255
        paint.color = Color.WHITE
        canvas.drawCircle(center, center, center, paint)
        paint.color = color
        canvas.drawCircle(center, center, center - dp(if (isFocused) 5 else 3), paint)
        return bitmap
    }

    private fun enterMeasurementMode() {
        if (TrackRecordingService.currentState.isRecording) {
            showSnackbar(getString(R.string.recording_already_started))
            return
        }
        clearTrackFocus()
        hideSection()
        isMeasurementMode = true
        measurementPoints.clear()
        measurementGeoPoints.clear()
        fixedMeasurementDistanceMeters = 0f
        measurementPolyline?.let { map.overlays.remove(it) }
        measurementPolyline = null
        bottomNavigation.visibility = View.GONE
        addTrackFab.setImageResource(R.drawable.ic_plus_circle)
        updateMeasurementRoute()
        syncRecordingState()
    }

    private fun exitMeasurementMode() {
        isMeasurementMode = false
        measurementPoints.clear()
        measurementGeoPoints.clear()
        fixedMeasurementDistanceMeters = 0f
        measurementPolyline?.let { map.overlays.remove(it) }
        measurementPolyline = null
        bottomNavigation.visibility = View.VISIBLE
        bottomNavigation.selectedItemId = R.id.navMap
        syncRecordingState()
        map.invalidate()
    }

    private fun addMeasurementPoint() {
        val point = measurementTrackPoint()
        measurementPoints.lastOrNull()?.let { previous ->
            fixedMeasurementDistanceMeters += segmentDistanceMeters(previous, point)
        }
        measurementPoints.add(point)
        measurementGeoPoints.add(point.toGeoPoint())
        updateMeasurementRoute()
        updateMeasurementControls()
        showMeasurementPointFlash()
    }

    private fun showMeasurementPointFlash() {
        val flash = View(this).apply {
            setBackgroundColor(Color.WHITE)
            alpha = 0f
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        contentHost.addView(
            flash,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        flash.animate()
            .alpha(0.32f)
            .setDuration(42L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                flash.animate()
                    .alpha(0f)
                    .setDuration(150L)
                    .setInterpolator(DecelerateInterpolator(1.8f))
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            contentHost.removeView(flash)
                        }
                    })
                    .start()
            }
            .start()
    }

    private fun undoMeasurementPoint() {
        if (measurementPoints.isEmpty()) return
        if (measurementPoints.size >= 2) {
            fixedMeasurementDistanceMeters -= segmentDistanceMeters(
                measurementPoints[measurementPoints.lastIndex - 1],
                measurementPoints.last()
            )
            fixedMeasurementDistanceMeters = fixedMeasurementDistanceMeters.coerceAtLeast(0f)
        }
        measurementPoints.removeAt(measurementPoints.lastIndex)
        if (measurementGeoPoints.isNotEmpty()) {
            measurementGeoPoints.removeAt(measurementGeoPoints.lastIndex)
        }
        updateMeasurementRoute()
        updateMeasurementControls()
    }

    private fun updateMeasurementRoute() {
        if (!isMeasurementMode) return
        val previewPoints = measurementPreviewGeoPoints()
        if (previewPoints.size >= 2) {
            val polyline = measurementPolyline ?: Polyline(map).apply {
                outlinePaint.color = TrackType.CUSTOM.color
                outlinePaint.strokeWidth = TRACK_STROKE_WIDTH
                outlinePaint.isAntiAlias = true
                setOnClickListener { _, _, _ ->
                    addMeasurementPoint()
                    true
                }
                measurementPolyline = this
                map.overlays.add(this)
            }
            polyline.setPoints(previewPoints)
        } else {
            measurementPolyline?.let { map.overlays.remove(it) }
            measurementPolyline = null
        }

        updateMeasurementPanel()
        map.invalidate()
    }

    private fun updateMeasurementPreview() {
        if (!isMeasurementMode || measurementPoints.isEmpty()) return
        val polyline = measurementPolyline ?: Polyline(map).apply {
            outlinePaint.color = TrackType.CUSTOM.color
            outlinePaint.strokeWidth = TRACK_STROKE_WIDTH
            outlinePaint.isAntiAlias = true
            setOnClickListener { _, _, _ ->
                addMeasurementPoint()
                true
            }
            measurementPolyline = this
            map.overlays.add(this)
        }
        polyline.setPoints(measurementGeoPoints + reticleTargetPoint())
        updateMeasurementPanel()
        map.invalidate()
    }

    private fun updateMeasurementControls() {
        measurementUndoFab.visibility = if (measurementPoints.isNotEmpty()) View.VISIBLE else View.GONE
        updateMeasurementPanel()
    }

    private fun measurementPreviewGeoPoints(): List<GeoPoint> {
        if (measurementPoints.isEmpty()) return emptyList()
        val target = measurementTrackPoint()
        val last = measurementPoints.last()
        return if (segmentDistanceMeters(last, target) < 0.5f) {
            measurementGeoPoints.toList()
        } else {
            measurementGeoPoints + target.toGeoPoint()
        }
    }

    private fun updateMeasurementPanel() {
        if (measurementPoints.isEmpty()) {
            measurementDistanceText.text = getString(R.string.measurement_first_point)
            measurementPointCountText.visibility = View.GONE
            measurementSaveButton.visibility = View.GONE
        } else {
            measurementDistanceText.text = formatDistance(measurementDistanceWithPreview())
            measurementPointCountText.text = formatPointCount(measurementPoints.size)
            measurementPointCountText.visibility = View.VISIBLE
            measurementSaveButton.visibility = View.VISIBLE
        }
    }

    private fun measurementPreviewPoints(): List<TrackPoint> {
        if (measurementPoints.isEmpty()) return emptyList()
        val target = measurementTrackPoint()
        val last = measurementPoints.last()
        return if (segmentDistanceMeters(last, target) < 0.5f) {
            measurementPoints.toList()
        } else {
            measurementPoints + target
        }
    }

    private fun measurementTrackPoint(): TrackPoint {
        val point = reticleTargetPoint()
        return TrackPoint(
            latitude = point.latitude,
            longitude = point.longitude,
            altitude = null,
            timeMillis = System.currentTimeMillis()
        )
    }

    private fun measurementDistanceWithPreview(): Float {
        val last = measurementPoints.lastOrNull() ?: return 0f
        return fixedMeasurementDistanceMeters + segmentDistanceMeters(last, measurementTrackPoint())
    }

    private fun showSaveMeasurementDialog() {
        if (measurementPoints.isEmpty()) return
        val savePoints = measurementPreviewPoints().ifEmpty { measurementPoints.toList() }
        val input = EditText(this).apply {
            setText(GpxTrackStore.defaultTrackName(TrackType.CUSTOM, savePoints.first().timeMillis))
            selectAll()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(20), 0, dp(20), 0)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(input)
                addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.point_emoji_hint)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_secondary))
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8) }
                })
            })
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.measurement_route_name_title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                saveMeasurementRoute(savePoints, input.text.toString().trim())
            }
            .show()
        input.post {
            input.requestFocus()
            input.selectAll()
            val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun saveMeasurementRoute(points: List<TrackPoint>, name: String) {
        if (points.isEmpty()) return
        val savedTrack = trackStore.saveTrack(
            type = TrackType.CUSTOM,
            points = points,
            distanceMeters = calculateRouteDistance(points),
            startedAtMillis = points.first().timeMillis,
            name = name.ifBlank { GpxTrackStore.defaultTrackName(TrackType.CUSTOM, points.first().timeMillis) }
        )
        allSavedTracks = (allSavedTracks.filterNot { it.file.name == savedTrack.file.name } + savedTrack)
            .sortedByDescending { it.startedAtMillis }
        hasLoadedSavedTracks = true
        applySavedTrackOverlays(savedTracksForMap())
        syncTracksSilently()
        loadSavedTracksAsync()
        exitMeasurementMode()
        focusTrack(savedTrack)
        elapsedHandler.postDelayed({
            if (focusedTrackFileName == savedTrack.file.name) clearTrackFocus()
        }, AUTO_TRACK_FOCUS_MS)
    }

    private fun calculateRouteDistance(points: List<TrackPoint>): Float {
        var distanceMeters = 0f
        points.zipWithNext { previous, current ->
            distanceMeters += segmentDistanceMeters(previous, current)
        }
        return distanceMeters
    }

    private fun segmentDistanceMeters(previous: TrackPoint, current: TrackPoint): Float {
        val result = FloatArray(1)
        Location.distanceBetween(
            previous.latitude,
            previous.longitude,
            current.latitude,
            current.longitude,
            result
        )
        return result[0]
    }

    private fun formatPointCount(count: Int): String {
        val word = when {
            count % 10 == 1 && count % 100 != 11 -> "точка"
            count % 10 in 2..4 && count % 100 !in 12..14 -> "точки"
            else -> "точек"
        }
        return "$count $word"
    }

    private fun showDestinationPreviewSheet(point: GeoPoint) {
        showDestinationSheet(
            title = getString(R.string.destination_preview_title),
            point = point,
            holePoint = null,
            holeOffsetYPx = 0,
            primaryText = getString(R.string.place_destination_marker),
            onPrimary = { setDestinationMarker(point) },
            secondaryText = getString(R.string.save_point),
            onSecondary = { showSavePointDialog(point) },
            tertiaryText = null,
            onTertiary = null,
            destructiveText = null,
            onDestructive = null
        )
    }

    private fun reticleTargetPoint(): GeoPoint {
        val center = map.mapCenter
        return GeoPoint(center.latitude, center.longitude)
    }

    private fun showDestinationDetailsSheet(point: GeoPoint) {
        showDestinationSheet(
            title = getString(R.string.destination_title),
            point = point,
            holePoint = null,
            holeOffsetYPx = 0,
            primaryText = null,
            onPrimary = null,
            secondaryText = getString(R.string.save_point),
            onSecondary = { showSavePointDialog(point) },
            tertiaryText = null,
            onTertiary = null,
            destructiveText = getString(R.string.delete_destination),
            onDestructive = ::clearDestinationMarker
        )
    }

    private fun showSavedPointDetailsSheet(point: SavedPoint) {
        showDestinationSheet(
            title = point.name,
            point = point.toGeoPoint(),
            holePoint = point.toGeoPoint(),
            holeOffsetYPx = savedPointHighlightOffset(point),
            primaryText = null,
            onPrimary = null,
            secondaryText = if (point.visible) getString(R.string.hide_from_map) else getString(R.string.show_on_map),
            onSecondary = {
                savedPointStore.setPointVisibility(point, !point.visible)
                skippedPointRefreshes = 1
                syncTracksSilently()
                loadSavedPointsOnMap()
                showPointsScreenIfVisible()
            },
            tertiaryText = getString(R.string.rename),
            onTertiary = { showRenamePointDialog(point) },
            destructiveText = getString(R.string.delete_destination),
            onDestructive = {
                savedPointStore.deletePoint(point)
                skippedPointRefreshes = 1
                syncTracksSilently()
                loadSavedPointsOnMap()
                showPointsScreenIfVisible()
            }
        )
    }

    private fun showDestinationSheet(
        title: String,
        point: GeoPoint,
        holePoint: GeoPoint?,
        holeOffsetYPx: Int = 0,
        primaryText: String?,
        onPrimary: (() -> Unit)?,
        secondaryText: String?,
        onSecondary: (() -> Unit)?,
        tertiaryText: String? = null,
        onTertiary: (() -> Unit)? = null,
        destructiveText: String?,
        onDestructive: (() -> Unit)?
    ) {
        val sheet = BottomSheetDialog(this)
        showDestinationDimOverlay(holePoint, holeOffsetYPx)
        sheet.setOnDismissListener {
            hideDestinationDimOverlay()
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bottom_sheet)
            setPadding(dp(20), dp(14), dp(20), dp(28))
            addView(View(this@MainActivity).apply {
                setBackgroundResource(R.drawable.bg_sheet_handle)
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(6)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(32)
                }
            })
            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
                textSize = 28f
                typeface = Typeface.DEFAULT
            })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.destination_distance, distanceToPoint(point))
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_primary))
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(12)
                    bottomMargin = dp(18)
                }
            })
            if (primaryText != null && onPrimary != null) {
                addView(MaterialButton(this@MainActivity).apply {
                    text = primaryText
                    isAllCaps = false
                    setOnClickListener {
                        sheet.dismiss()
                        onPrimary()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(52)
                    )
                })
            }
            if (secondaryText != null && onSecondary != null) {
                addView(MaterialButton(this@MainActivity).apply {
                    text = secondaryText
                    isAllCaps = false
                    backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, android.R.color.transparent)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_purple_ink))
                    elevation = 0f
                    stateListAnimator = null
                    setOnClickListener {
                        sheet.dismiss()
                        onSecondary()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(52)
                    ).apply { topMargin = dp(6) }
                })
            }
            if (tertiaryText != null && onTertiary != null) {
                addView(MaterialButton(this@MainActivity).apply {
                    text = tertiaryText
                    isAllCaps = false
                    backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, android.R.color.transparent)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_purple_ink))
                    elevation = 0f
                    stateListAnimator = null
                    setOnClickListener {
                        sheet.dismiss()
                        onTertiary()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(52)
                    ).apply { topMargin = dp(6) }
                })
            }
            if (destructiveText != null && onDestructive != null) {
                addView(MaterialButton(this@MainActivity).apply {
                    text = destructiveText
                    isAllCaps = false
                    backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, android.R.color.transparent)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_danger))
                    elevation = 0f
                    stateListAnimator = null
                    setOnClickListener {
                        sheet.dismiss()
                        onDestructive()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(52)
                    ).apply { topMargin = dp(6) }
                })
            }
        }
        sheet.setContentView(content)
        sheet.setOnShowListener { dialog ->
            sheet.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val bottomSheet = (dialog as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.background = ColorDrawable(Color.TRANSPARENT)
        }
        sheet.show()
    }

    private fun showSavePointDialog(point: GeoPoint) {
        val defaultName = SavedPointStore.defaultPointName(System.currentTimeMillis())
        val input = EditText(this).apply {
            setText(defaultName)
            hint = getString(R.string.point_name)
            selectAll()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(20), 0, dp(20), 0)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(input)
                addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.point_emoji_hint)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_secondary))
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8) }
                })
            })
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.save_point)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val savedPoint = savedPointStore.savePoint(input.text.toString().trim(), point)
                showSnackbar(getString(R.string.point_saved, savedPoint.name))
                syncTracksSilently()
                loadSavedPointsOnMap()
                if (currentSection == Section.POINTS) showPointsScreen()
            }
            .create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            input.postDelayed({
                input.requestFocus()
                input.selectAll()
                val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                inputMethodManager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }, 120L)
        }
        dialog.show()
    }

    private fun showDestinationDimOverlay(holePoint: GeoPoint? = null, holeOffsetYPx: Int = 0) {
        hideDestinationDimOverlay()
        val holeOnScreen = holePoint?.let { point ->
            val pointOnMap = Point().also { map.projection.toPixels(point, it) }
            val mapLocation = IntArray(2)
            val hostLocation = IntArray(2)
            map.getLocationOnScreen(mapLocation)
            contentHost.getLocationOnScreen(hostLocation)
            Point(
                mapLocation[0] - hostLocation[0] + pointOnMap.x,
                mapLocation[1] - hostLocation[1] + pointOnMap.y + holeOffsetYPx
            )
        }
        val overlay = ReticleDimOverlay(this).apply {
            setHoleCenter(holeOnScreen?.x?.toFloat(), holeOnScreen?.y?.toFloat())
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            alpha = 0f
            elevation = dp(9).toFloat()
        }
        destinationDimOverlay = overlay
        contentHost.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        overlay.animate()
            .alpha(1f)
            .setDuration(140L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun savedPointHighlightOffset(point: SavedPoint): Int {
        return if (leadingEmoji(point.name) == null) -dp(14) else 0
    }

    private fun hideDestinationDimOverlay() {
        val overlay = destinationDimOverlay ?: return
        destinationDimOverlay = null
        overlay.animate()
            .alpha(0f)
            .setDuration(110L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                contentHost.removeView(overlay)
            }
            .start()
    }

    private fun setDestinationMarker(point: GeoPoint) {
        clearDestinationMarker()
        val marker = Marker(map).apply {
            position = point
            icon = BitmapDrawable(resources, createDestinationFlagIcon())
            setAnchor(0.18f, 1f)
            setOnMarkerClickListener { _, _ ->
                showDestinationDetailsSheet(point)
                true
            }
        }
        destinationMarker = marker
        map.overlays.add(marker)
        map.invalidate()
    }

    private fun clearDestinationMarker() {
        destinationMarker?.let { map.overlays.remove(it) }
        destinationMarker = null
        map.invalidate()
    }

    private fun loadSavedPointsOnMap() {
        savedPointOverlays.forEach { map.overlays.remove(it) }
        savedPointOverlays.clear()
        savedPointStore.loadPoints()
            .filter { it.visible }
            .forEach { point ->
                val marker = Marker(map).apply {
                    position = point.toGeoPoint()
                    title = point.name
                    icon = BitmapDrawable(resources, createSavedPointIcon(point.name))
                    if (leadingEmoji(point.name) == null) {
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    } else {
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    }
                    setOnMarkerClickListener { _, _ ->
                        showSavedPointDetailsSheet(point)
                        true
                    }
                }
                savedPointOverlays.add(marker)
                map.overlays.add(marker)
            }
        focusedTrackFileName?.let {
            applySavedTrackOverlays(savedTracksForMap())
            return
        }
        map.invalidate()
    }

    private fun createSavedPointIcon(pointName: String): Bitmap {
        val emoji = leadingEmoji(pointName)
        val size = dp(if (emoji != null) 42 else 34)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val center = size / 2f

        if (emoji != null) {
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(center, center, dp(18).toFloat(), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.color = ContextCompat.getColor(this, R.color.icu_purple_ink)
            canvas.drawCircle(center, center, dp(18).toFloat() - paint.strokeWidth / 2f, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.BLACK
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = dp(24).toFloat()
            val textY = center - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(emoji, center, textY, paint)
        } else {
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(center, center, dp(10).toFloat(), paint)
            paint.color = Color.rgb(214, 38, 42)
            canvas.drawCircle(center, center, dp(6).toFloat(), paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.color = Color.rgb(60, 47, 47)
            canvas.drawLine(center, center + dp(9), center, size - dp(2).toFloat(), paint)
        }
        return bitmap
    }

    private fun leadingEmoji(value: String): String? {
        val trimmed = value.trimStart()
        if (trimmed.isEmpty()) return null
        val firstCodePoint = trimmed.codePointAt(0)
        val type = Character.getType(firstCodePoint)
        val isEmojiLike = type == Character.OTHER_SYMBOL.toInt() ||
            firstCodePoint in 0x1F000..0x1FAFF ||
            firstCodePoint in 0x2600..0x27BF
        if (!isEmojiLike) return null

        val end = Character.charCount(firstCodePoint)
        val nextCodePoint = trimmed.codePointAtOrNull(end)
        val emojiEnd = if (nextCodePoint == 0xFE0F) end + Character.charCount(nextCodePoint) else end
        return trimmed.substring(0, emojiEnd)
    }

    private fun String.codePointAtOrNull(index: Int): Int? {
        return if (index in indices) codePointAt(index) else null
    }

    private fun createDestinationFlagIcon(): Bitmap {
        val width = dp(34)
        val height = dp(42)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val poleX = dp(7).toFloat()
        val top = dp(4).toFloat()
        val bottom = dp(38).toFloat()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2).toFloat()
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.rgb(60, 47, 47)
        canvas.drawLine(poleX, top, poleX, bottom, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(214, 38, 42)
        val flag = android.graphics.Path().apply {
            moveTo(poleX, top)
            lineTo(width - dp(4).toFloat(), top + dp(4))
            lineTo(poleX, top + dp(15))
            close()
        }
        canvas.drawPath(flag, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(48, 0, 0, 0)
        canvas.drawOval(
            poleX - dp(4),
            bottom - dp(1),
            poleX + dp(12),
            bottom + dp(4),
            paint
        )
        return bitmap
    }

    private fun showTracksScreen() {
        currentSection = Section.TRACKS
        val tracks = allSavedTracks
        showSection(getString(R.string.my_tracks))
        setSectionContentPadding(horizontalDp = 0)

        if (!hasLoadedSavedTracks && tracks.isEmpty()) {
            sectionContent.addView(emptyStateText(getString(R.string.loading)))
            loadSavedTracksAsync()
            return
        }

        if (tracks.isEmpty()) {
            sectionContent.addView(emptyStateText(getString(R.string.no_tracks)))
            return
        }

        val pages = listOf(
            TrackListPage(getString(R.string.walk_tracks), TrackType.WALK),
            TrackListPage(getString(R.string.bike_tracks), TrackType.BIKE),
            TrackListPage(getString(R.string.manual_tracks), TrackType.CUSTOM)
        )
        val tabs = TabLayout(this).apply {
            tabMode = TabLayout.MODE_FIXED
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        lateinit var renderTrackTab: (Int) -> Unit
        val searchInput = searchField(
            hint = getString(R.string.search_tracks),
            includeCalendar = true,
            initialValue = trackSearchQuery,
            onCalendarClick = { input -> showTrackDatePicker(input) }
        ) { value ->
            trackSearchQuery = value
            renderTrackTab(selectedTrackListTab)
        }
        val listHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        sectionContent.addView(tabs)
        sectionContent.addView(searchInput.first)
        sectionContent.addView(listHost)
        pages.forEach { page -> tabs.addTab(tabs.newTab().setText(page.title)) }
        renderTrackTab = { position ->
            selectedTrackListTab = position
            listHost.removeAllViews()
            listHost.addView(tracksPageView(pages[position].type, tracks, trackSearchQuery))
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                renderTrackTab(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        tabs.getTabAt(selectedTrackListTab.coerceIn(0, pages.lastIndex))?.select()
        renderTrackTab(selectedTrackListTab.coerceIn(0, pages.lastIndex))
    }

    private fun tracksPageView(type: TrackType, tracks: List<RecordedTrack>, query: String = ""): View {
        val pageTracks = tracks
            .filter { it.type == type }
            .filter { trackMatchesQuery(it, query) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(32))
        }

        if (pageTracks.isEmpty()) {
            content.addView(emptyStateText(getString(R.string.no_tracks)))
            return content
        }

        pageTracks.groupBy { GpxTrackStore.monthKey(it) }
            .toSortedMap(compareByDescending<YearMonth> { it.year }.thenByDescending { it.monthValue })
            .forEach { (month, monthTracks) ->
                content.addView(trackGroupTitle(formatMonthTitle(month)))
                monthTracks.sortedByDescending { it.startedAtMillis }.forEach { track ->
                    content.addView(trackCard(track))
                }
        }
        return content
    }

    private fun trackMatchesQuery(track: RecordedTrack, query: String): Boolean {
        val normalizedQuery = query.trim().lowercase(Locale.forLanguageTag("ru-RU"))
        if (normalizedQuery.isBlank()) return true
        val day = DateTimeFormatter
            .ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru-RU"))
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(track.startedAtMillis))
        val compactDay = DateTimeFormatter
            .ofPattern("dd.MM.yyyy", Locale.forLanguageTag("ru-RU"))
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(track.startedAtMillis))
        val haystack = listOf(
            track.name,
            track.type.title,
            formatTrackDate(track.startedAtMillis),
            day,
            compactDay,
            GpxTrackStore.monthKey(track).toString()
        ).joinToString(" ").lowercase(Locale.forLanguageTag("ru-RU"))
        return haystack.contains(normalizedQuery)
    }

    private fun showTrackDatePicker(input: TextInputEditText) {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(getString(R.string.pick_track_date))
            .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            val selectedDate = Instant.ofEpochMilli(selection)
                .atZone(ZoneId.of("UTC"))
                .toLocalDate()
            val formatted = selectedDate.format(
                DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru-RU"))
            )
            input.setText(formatted)
            input.setSelection(input.text?.length ?: 0)
        }
        picker.show(supportFragmentManager, "track_date_picker")
    }

    private fun showStatisticsScreen() {
        currentSection = Section.STATISTICS
        val tracks = trackStore.loadTracks().filter { it.type != TrackType.CUSTOM }
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

    private fun showPointsScreen() {
        currentSection = Section.POINTS
        val points = savedPointStore.loadPoints()
        showSection(getString(R.string.points))
        setSectionContentPadding(horizontalDp = 0)

        lateinit var renderPoints: () -> Unit
        val searchInput = searchField(
            hint = getString(R.string.search_points),
            includeCalendar = false,
            initialValue = pointSearchQuery
        ) { value ->
            pointSearchQuery = value
            renderPoints()
        }
        val listHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        sectionContent.addView(searchInput.first)
        sectionContent.addView(listHost)

        renderPoints = render@{
            listHost.removeAllViews()
            if (points.isEmpty()) {
                listHost.addView(emptyStateText(getString(R.string.no_saved_points)))
                return@render
            }
            val filteredPoints = points.filter { point ->
                pointSearchQuery.isBlank() ||
                    point.name.lowercase(Locale.forLanguageTag("ru-RU"))
                        .contains(pointSearchQuery.trim().lowercase(Locale.forLanguageTag("ru-RU")))
            }
            if (filteredPoints.isEmpty()) {
                listHost.addView(emptyStateText(getString(R.string.no_saved_points)))
                return@render
            }

            val recyclerView = RecyclerView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels - dp(190)).coerceAtLeast(dp(420))
                )
                layoutManager = LinearLayoutManager(this@MainActivity)
                setPadding(dp(20), 0, dp(20), dp(32))
                clipToPadding = false
                itemAnimator = null
            }
            val adapter = SavedPointAdapter(filteredPoints.toMutableList())
            recyclerView.adapter = adapter
            if (pointSearchQuery.isBlank()) {
                val callback = SavedPointDragCallback(adapter)
                val touchHelper = ItemTouchHelper(callback)
                touchHelper.attachToRecyclerView(recyclerView)
                adapter.dragStarter = { holder ->
                    holder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    touchHelper.startDrag(holder)
                }
            }
            listHost.addView(recyclerView)
        }
        renderPoints()
    }

    private fun savedPointCard(point: SavedPoint): View {
        return MaterialCardView(this).apply {
            radius = dp(8).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.icu_card_surface))
            isClickable = true
            isFocusable = true
            setOnClickListener { showSavedPointOnMap(point) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(8), dp(12))

                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@MainActivity).apply {
                        text = point.name
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
                        textSize = 17f
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = getString(R.string.destination_distance, distanceToPoint(point.toGeoPoint()))
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_secondary))
                        textSize = 14f
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(4) }
                    })
                })

                if (!point.visible) {
                    addView(ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_eye_off)
                        imageTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.icu_text_secondary)
                        alpha = 0.3f
                        contentDescription = getString(R.string.hidden_on_map)
                        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                            rightMargin = dp(4)
                        }
                    })
                }

                addView(MaterialButton(this@MainActivity).apply {
                    backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, android.R.color.transparent)
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_transparent)
                    elevation = 0f
                    stateListAnimator = null
                    minWidth = 0
                    minimumWidth = 0
                    minimumHeight = dp(40)
                    setPadding(0, 0, 0, 0)
                    contentDescription = getString(R.string.point_actions)
                    setIconResource(R.drawable.ic_kebab_vertical)
                    iconTint = ContextCompat.getColorStateList(this@MainActivity, R.color.icu_purple_ink)
                    iconPadding = 0
                    setOnClickListener { anchor -> showPointMenu(anchor, point) }
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                })
            })
        }
    }

    private fun showSavedPointOnMap(point: SavedPoint) {
        hideSection()
        bottomNavigation.selectedItemId = R.id.navMap
        map.controller.animateTo(point.toGeoPoint())
    }

    private fun showPointMenu(anchor: View, point: SavedPoint) {
        PopupMenu(this, anchor).apply {
            menu.add(0, POINT_ACTION_RENAME, 0, R.string.rename)
            menu.add(
                0,
                POINT_ACTION_VISIBILITY,
                1,
                if (point.visible) R.string.hide_from_map else R.string.show_on_map
            )
            menu.add(0, POINT_ACTION_DELETE, 2, R.string.delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    POINT_ACTION_RENAME -> showRenamePointDialog(point)
                    POINT_ACTION_VISIBILITY -> {
                        savedPointStore.setPointVisibility(point, !point.visible)
                        skippedPointRefreshes = 1
                        syncTracksSilently()
                        loadSavedPointsOnMap()
                        showPointsScreen()
                    }
                    POINT_ACTION_DELETE -> showDeletePointDialog(point)
                }
                true
            }
            show()
        }
    }

    private fun showRenamePointDialog(point: SavedPoint) {
        val input = EditText(this).apply {
            setText(point.name)
            selectAll()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(20), 0, dp(20), 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_point)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                savedPointStore.renamePoint(point, input.text.toString().trim())
                skippedPointRefreshes = 1
                syncTracksSilently()
                loadSavedPointsOnMap()
                showPointsScreen()
            }
            .show()
    }

    private fun showDeletePointDialog(point: SavedPoint) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_point_title)
            .setMessage(R.string.delete_point_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                savedPointStore.deletePoint(point)
                skippedPointRefreshes = 1
                syncTracksSilently()
                loadSavedPointsOnMap()
                showPointsScreen()
            }
            .show()
    }

    private fun showToolsScreen() {
        currentSection = Section.SETTINGS
        showSection(getString(R.string.tools))
        setSectionContentPadding(horizontalDp = 20)
        sectionContent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = (resources.displayMetrics.heightPixels - dp(260)).coerceAtLeast(dp(360))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(rulerToolRow())
            addView(settingsRow())
            addView(View(this@MainActivity), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.app_version, BuildConfig.VERSION_NAME)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_secondary))
                textSize = 12f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener { showChangelogSheet() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(24)
                    bottomMargin = dp(8)
                }
            })
        })
    }

    private fun rulerToolRow(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_tools)
                imageTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.icu_purple_ink)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    rightMargin = dp(12)
                }
                setPadding(dp(7), dp(7), dp(7), dp(7))
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.ruler_tool_title)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
                    textSize = 17f
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.ruler_tool_subtitle)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_secondary))
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(2)
                    }
                })
            })
        }
        return com.google.android.material.card.MaterialCardView(this).apply {
            radius = dp(8).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.icu_card_surface))
            isClickable = true
            isFocusable = true
            setOnClickListener { enterMeasurementMode() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
            addView(content)
        }
    }

    private fun showChangelogSheet() {
        val sheet = BottomSheetDialog(this)
        val changelog = runCatching {
            assets.open(CHANGELOG_ASSET_NAME).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText()
            }
        }.getOrElse {
            getString(R.string.unknown_error)
        }
        lateinit var handle: View
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bottom_sheet)
            setPadding(dp(20), dp(14), dp(20), dp(24))
            handle = View(this@MainActivity).apply {
                setBackgroundResource(R.drawable.bg_sheet_handle)
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> sheet.behavior.isDraggable = true
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            post { sheet.behavior.isDraggable = false }
                        }
                    }
                    false
                }
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(6)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(28)
                }
            }
            addView(handle)
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.changelog_title)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
                textSize = 28f
                typeface = Typeface.DEFAULT
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(18) }
            })
            addView(ScrollView(this@MainActivity).apply {
                isFillViewport = false
                addView(TextView(this@MainActivity).apply {
                    text = formatChangelogMarkdown(changelog)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_primary))
                    textSize = 15f
                    setLineSpacing(dp(2).toFloat(), 1.0f)
                })
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.64f).toInt()
                )
            })
        }

        sheet.setContentView(content)
        sheet.setOnShowListener { dialog ->
            val bottomSheet = (dialog as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.background = ColorDrawable(Color.TRANSPARENT)
            sheet.behavior.isDraggable = false
        }
        sheet.show()
    }

    private fun formatChangelogMarkdown(markdown: String): CharSequence {
        val result = SpannableStringBuilder()
        markdown.lines().forEach { rawLine ->
            val line = rawLine.trimEnd()
            when {
                line.isBlank() -> {
                    if (result.isNotEmpty() && !result.endsWith("\n\n")) {
                        result.append("\n")
                    }
                }
                line.startsWith("# ") -> appendMarkdownLine(
                    result,
                    text = line.removePrefix("# ").trim(),
                    relativeSize = 1.45f,
                    isBold = true,
                    bottomBreaks = 2
                )
                line.startsWith("## ") -> appendMarkdownLine(
                    result,
                    text = line.removePrefix("## ").trim(),
                    relativeSize = 1.28f,
                    isBold = true,
                    bottomBreaks = 2
                )
                line.startsWith("### ") -> appendMarkdownLine(
                    result,
                    text = line.removePrefix("### ").trim(),
                    relativeSize = 1.12f,
                    isBold = true,
                    bottomBreaks = 1
                )
                line.startsWith("- ") -> appendMarkdownLine(
                    result,
                    text = line.removePrefix("- ").trim(),
                    bullet = true,
                    bottomBreaks = 1
                )
                else -> appendMarkdownLine(result, text = line, bottomBreaks = 2)
            }
        }
        return result
    }

    private fun appendMarkdownLine(
        builder: SpannableStringBuilder,
        text: String,
        relativeSize: Float? = null,
        isBold: Boolean = false,
        bullet: Boolean = false,
        bottomBreaks: Int
    ) {
        val start = builder.length
        builder.append(text)
        val end = builder.length
        if (isBold) {
            builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (relativeSize != null) {
            builder.setSpan(RelativeSizeSpan(relativeSize), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (bullet) {
            builder.setSpan(BulletSpan(dp(14)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        repeat(bottomBreaks) {
            builder.append("\n")
        }
    }

    private fun showProfileScreen() {
        currentSection = Section.PROFILE
        showSection(getString(R.string.profile))
        setSectionContentPadding(horizontalDp = 20)
        val session = sessionStore.current()
        if (session == null) {
            sectionContent.addView(profileStatusCard(getString(R.string.sign_in_for_sync)))
            sectionContent.addView(statisticsEntryCard())
            sectionContent.addView(primaryFullWidthButton(getString(R.string.sign_in)) {
                showAuthEntry()
            })
            return
        }

        val cachedProfile = cachedUserProfile
        if (cachedProfile != null) {
            renderProfileWithFriends(cachedProfile, cachedFriends)
            loadProfileAndFriends(force = false, showErrors = false)
            return
        }

        sectionContent.addView(profileStatusCard(
            textValue = session.email ?: session.userId,
            onSignOut = ::signOut
        ))
        sectionContent.addView(statisticsEntryCard())
        sectionContent.addView(primaryFullWidthButton(getString(R.string.add_friend_by_link)) {
            shareFriendInvite()
        })
        sectionContent.addView(groupTitle(getString(R.string.friends)))
        sectionContent.addView(locationBroadcastRow())
        sectionContent.addView(emptyStateText(getString(R.string.loading)))
        loadProfileAndFriends(force = true, showErrors = true)
    }

    private fun loadProfileAndFriends(force: Boolean = false, showErrors: Boolean = false) {
        val session = sessionStore.current() ?: return
        val now = System.currentTimeMillis()
        if (!force && cachedUserProfile != null && now - lastProfileRefreshMillis < PROFILE_REFRESH_MS) {
            return
        }
        if (profileRefreshInFlight) return
        profileRefreshInFlight = true
        backgroundExecutor.execute {
            runCatching {
                val activeSession = supabaseClient.activeSession()
                supabaseClient.fetchMyProfile(activeSession) to supabaseClient.fetchFriends(activeSession)
            }.onSuccess { (profile, friends) ->
                runOnUiThread {
                    profileRefreshInFlight = false
                    cachedUserProfile = profile
                    cachedFriends = friends
                    lastProfileRefreshMillis = System.currentTimeMillis()
                    if (currentSection == Section.PROFILE) {
                        renderProfileWithFriends(profile, friends)
                    }
                    refreshFriendLocationsForFriends(friends)
                }
            }.onFailure { error ->
                runOnUiThread {
                    profileRefreshInFlight = false
                    if (showErrors && currentSection == Section.PROFILE) {
                        showSnackbar(getString(R.string.sync_failed, userMessage(error)), isLong = true)
                    }
                }
            }
        }
    }

    private fun renderProfileWithFriends(profile: UserProfile, friends: List<FriendProfile>) {
        sectionContent.removeAllViews()
        setSectionContentPadding(horizontalDp = 20)
        sectionContent.addView(profileStatusCard(
            textValue = profile.displayName,
            subtitle = profile.email,
            onEdit = { showEditMyProfileDialog(profile) },
            onSignOut = ::signOut
        ))
        sectionContent.addView(statisticsEntryCard())
        sectionContent.addView(primaryFullWidthButton(getString(R.string.add_friend_by_link)) {
            shareFriendInvite()
        })
        sectionContent.addView(groupTitle(getString(R.string.friends)))
        sectionContent.addView(locationBroadcastRow())
        if (friends.isEmpty()) {
            sectionContent.addView(emptyStateText(getString(R.string.no_friends)))
        } else {
            friends.forEach { friend ->
                sectionContent.addView(friendCard(friend, friend.friendshipId == highlightedFriendshipId))
            }
        }
        if (highlightedFriendshipId != null) {
            elapsedHandler.postDelayed({
                highlightedFriendshipId = null
                if (currentSection == Section.PROFILE) showProfileScreen()
            }, FRIEND_HIGHLIGHT_DURATION_MS)
        }
    }

    private fun signOut() {
        sessionStore.clear()
        cachedUserProfile = null
        cachedFriends = emptyList()
        cachedFriendLocations = emptyMap()
        lastProfileRefreshMillis = 0L
        clearFriendLocationOverlays()
        stopForegroundLiveLocationUpdates()
        if (LocationBroadcastService.state(this).isActive) {
            stopLocationBroadcast(refreshProfile = false)
        }
        showProfileScreen()
    }

    private fun invalidateProfileCache() {
        cachedUserProfile = null
        cachedFriends = emptyList()
        lastProfileRefreshMillis = 0L
    }

    private fun locationBroadcastRow(): View {
        val state = LocationBroadcastService.state(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_track_cell)
            setPadding(dp(14), dp(10), dp(8), dp(10))
            setOnClickListener {
                if (LocationBroadcastService.state(this@MainActivity).isActive) {
                    confirmStopLocationBroadcast()
                } else {
                    showLocationBroadcastDurationSheet()
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.location_broadcast_title)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            })
            if (state.isActive) {
                addView(TextView(this@MainActivity).apply {
                    text = locationBroadcastCaption(state)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_secondary))
                    textSize = 13f
                })
            }
        })
        row.addView(com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
            isChecked = state.isActive
            isClickable = false
        })
        return row
    }

    private fun locationBroadcastCaption(state: LocationBroadcastState): String {
        val endsAt = state.endsAtMillis
            ?: return getString(R.string.location_broadcast_active_manual)
        val remaining = (endsAt - System.currentTimeMillis()).coerceAtLeast(0L)
        return getString(
            R.string.location_broadcast_active_time,
            LocationBroadcastService.formatRemainingTime(remaining)
        )
    }

    private fun showLocationBroadcastDurationSheet() {
        val sheet = BottomSheetDialog(this)
        val options = locationBroadcastOptions()
        var selectedDurationMs = options.first().second
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bottom_sheet)
            setPadding(dp(20), dp(14), dp(20), dp(28))
            addView(View(this@MainActivity).apply {
                setBackgroundResource(R.drawable.bg_sheet_handle)
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(6)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(36)
                }
            })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.location_broadcast_duration_title)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
                textSize = 28f
                typeface = Typeface.DEFAULT
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(18)
                }
            })
            val radioGroup = RadioGroup(this@MainActivity).apply {
                orientation = RadioGroup.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(18)
                }
            }
            options.forEachIndexed { index, option ->
                val radioButton = MaterialRadioButton(this@MainActivity).apply {
                    id = View.generateViewId()
                    text = option.first
                    textSize = 16f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_primary))
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = dp(48)
                    setPadding(0, 0, 0, 0)
                    layoutParams = RadioGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(48)
                    )
                }
                radioButton.setOnClickListener {
                    selectedDurationMs = option.second
                }
                radioGroup.addView(radioButton)
                if (index == 0) {
                    radioGroup.check(radioButton.id)
                }
            }
            radioGroup.setOnCheckedChangeListener { group, checkedId ->
                val checkedIndex = (0 until group.childCount).indexOfFirst { index ->
                    group.getChildAt(index).id == checkedId
                }
                selectedDurationMs = options.getOrNull(checkedIndex)?.second
            }
            addView(radioGroup)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                addView(MaterialButton(this@MainActivity).apply {
                    text = getString(R.string.cancel)
                    isAllCaps = false
                    backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, android.R.color.transparent)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_purple_ink))
                    elevation = 0f
                    stateListAnimator = null
                    setOnClickListener { sheet.dismiss() }
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        dp(48),
                        1f
                    ).apply {
                        marginEnd = dp(8)
                    }
                })
                addView(MaterialButton(this@MainActivity).apply {
                    text = getString(R.string.location_broadcast_enable)
                    isAllCaps = false
                    setOnClickListener {
                        sheet.dismiss()
                        requestStartLocationBroadcast(selectedDurationMs)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        dp(48),
                        1f
                    )
                })
            })
        }
        sheet.setContentView(content)
        sheet.setOnShowListener { dialog ->
            val bottomSheet = (dialog as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.background = ColorDrawable(Color.TRANSPARENT)
        }
        sheet.show()
    }

    private fun locationBroadcastOptions(): List<Pair<String, Long?>> {
        return listOf(
            getString(R.string.location_broadcast_15_minutes) to 15 * 60_000L,
            getString(R.string.location_broadcast_1_hour) to 60 * 60_000L,
            getString(R.string.location_broadcast_4_hours) to 4 * 60 * 60_000L,
            getString(R.string.location_broadcast_8_hours) to 8 * 60 * 60_000L,
            getString(R.string.location_broadcast_until_manual_option) to null
        )
    }

    private fun statisticsEntryCard(): View {
        return MaterialCardView(this).apply {
            radius = dp(8).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.icu_card_surface))
            isClickable = true
            isFocusable = true
            setOnClickListener { showStatisticsScreen() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_stats)
                    imageTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.icu_purple_ink)
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                        rightMargin = dp(12)
                    }
                    setPadding(dp(7), dp(7), dp(7), dp(7))
                })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@MainActivity).apply {
                        text = getString(R.string.statistics)
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
                        textSize = 17f
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = getString(R.string.statistics_profile_subtitle)
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_secondary))
                        textSize = 13f
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(2) }
                    })
                })
            })
        }
    }

    private fun requestStartLocationBroadcast(durationMs: Long?) {
        if (sessionStore.current() == null) {
            showSnackbar(getString(R.string.sign_in_for_sync), isLong = true)
            return
        }
        hasPendingLocationBroadcastStart = true
        pendingLocationBroadcastDurationMs = durationMs
        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }
        continuePendingLocationBroadcastStart()
    }

    private fun continuePendingLocationBroadcastStart() {
        if (!hasPendingLocationBroadcastStart) return
        if (!hasNotificationPermission()) {
            requestNotificationPermission()
            return
        }
        startPendingLocationBroadcast()
    }

    private fun startPendingLocationBroadcast() {
        if (!hasPendingLocationBroadcastStart) return
        val durationMs = pendingLocationBroadcastDurationMs
        hasPendingLocationBroadcastStart = false
        pendingLocationBroadcastDurationMs = null

        val intent = Intent(this, LocationBroadcastService::class.java).apply {
            action = LocationBroadcastService.ACTION_START
            durationMs?.let { putExtra(LocationBroadcastService.EXTRA_DURATION_MS, it) }
        }
        ContextCompat.startForegroundService(this, intent)
        showProfileScreen()
    }

    private fun confirmStopLocationBroadcast() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.location_broadcast_stop_title)
            .setMessage(R.string.location_broadcast_stop_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.location_broadcast_stop_action) { _, _ ->
                stopLocationBroadcast()
            }
            .show()
    }

    private fun stopLocationBroadcast(refreshProfile: Boolean = true) {
        val intent = Intent(this, LocationBroadcastService::class.java).apply {
            action = LocationBroadcastService.ACTION_STOP
        }
        startService(intent)
        if (refreshProfile) {
            showProfileScreen()
        }
    }

    private fun shareFriendInvite() {
        backgroundExecutor.execute {
            runCatching {
                val session = supabaseClient.activeSession()
                val token = supabaseClient.createFriendInvite(session)
                "${SupabaseConfig.FRIEND_INVITE_URL}?token=$token"
            }.onSuccess { link ->
                runOnUiThread {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, getString(R.string.friend_invite_share_text, link))
                    }
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.add_friend_by_link)))
                }
            }.onFailure { error ->
                runOnUiThread {
                    showSnackbar(getString(R.string.sync_failed, userMessage(error)), isLong = true)
                }
            }
        }
    }

    private fun showEditMyProfileDialog(profile: UserProfile) {
        showEditNameSheet(
            firstName = profile.firstName,
            lastName = profile.lastName,
            onSave = ::updateMyProfile
        )
    }

    private fun updateMyProfile(firstName: String, lastName: String) {
        backgroundExecutor.execute {
            runCatching {
                supabaseClient.updateMyProfile(supabaseClient.activeSession(), firstName, lastName)
            }.onSuccess {
                runOnUiThread {
                    invalidateProfileCache()
                    showProfileScreen()
                }
            }.onFailure { error ->
                runOnUiThread {
                    showSnackbar(getString(R.string.sync_failed, userMessage(error)), isLong = true)
                }
            }
        }
    }

    private fun showEditFriendNameDialog(friend: FriendProfile) {
        showEditNameSheet(
            firstName = friend.displayFirstName,
            lastName = friend.displayLastName
        ) { firstName, lastName ->
            setFriendAlias(friend, firstName, lastName)
        }
    }

    private fun showEditNameSheet(
        firstName: String,
        lastName: String,
        onSave: (String, String) -> Unit
    ) {
        val sheet = BottomSheetDialog(this)
        val firstNameInput = nameSheetInput(firstName)
        val lastNameInput = nameSheetInput(lastName)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bottom_sheet)
            setPadding(dp(12), dp(14), dp(12), dp(32))
            addView(View(this@MainActivity).apply {
                setBackgroundResource(R.drawable.bg_sheet_handle)
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(6)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(72)
                }
            })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.edit_name_sheet_title)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
                textSize = 28f
                typeface = Typeface.DEFAULT
                includeFontPadding = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
            addView(nameSheetInputLayout(getString(R.string.first_name), firstNameInput).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(26) }
            })
            addView(nameSheetInputLayout(getString(R.string.last_name), lastNameInput).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            })
            addView(MaterialButton(this@MainActivity).apply {
                text = getString(R.string.save)
                isAllCaps = false
                textSize = 16f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_primary))
                backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.icu_purple_surface)
                cornerRadius = dp(8)
                setOnClickListener {
                    sheet.dismiss()
                    onSave(
                        firstNameInput.text?.toString().orEmpty(),
                        lastNameInput.text?.toString().orEmpty()
                    )
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(56)
                ).apply { topMargin = dp(18) }
            })
        }

        sheet.setContentView(content)
        sheet.setOnShowListener { dialog ->
            sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            val bottomSheet = (dialog as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.background = ColorDrawable(Color.TRANSPARENT)
            firstNameInput.post {
                firstNameInput.requestFocus()
                firstNameInput.selectAll()
                val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                inputMethodManager.showSoftInput(firstNameInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        sheet.show()
    }

    private fun nameSheetInput(value: String): TextInputEditText {
        return TextInputEditText(this).apply {
            setText(value)
            setSingleLine(true)
            setSelectAllOnFocus(true)
            textSize = 20f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setPadding(dp(16), 0, dp(16), 0)
        }
    }

    private fun nameSheetInputLayout(label: String, input: TextInputEditText): TextInputLayout {
        return TextInputLayout(this).apply {
            hint = label
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = ContextCompat.getColor(this@MainActivity, R.color.icu_sheet_surface)
            setBoxCornerRadii(dp(4).toFloat(), dp(4).toFloat(), dp(4).toFloat(), dp(4).toFloat())
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(64)
            ))
            endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
        }
    }

    private fun acceptPendingInviteIfNeeded() {
        val token = pendingInviteToken ?: return
        acceptFriendInvite(token)
    }

    private fun acceptFriendInvite(token: String) {
        backgroundExecutor.execute {
            runCatching {
                supabaseClient.acceptFriendInvite(supabaseClient.activeSession(), token)
            }.onSuccess { friendshipId ->
                pendingInviteToken = null
                highlightedFriendshipId = friendshipId
                runOnUiThread {
                    invalidateProfileCache()
                    bottomNavigation.selectedItemId = R.id.navProfile
                    showSnackbar(getString(R.string.friend_added), isLong = true)
                    showProfileScreen()
                }
            }.onFailure { error ->
                runOnUiThread {
                    showSnackbar(getString(R.string.sync_failed, userMessage(error)), isLong = true)
                }
            }
        }
    }

    private fun showFriendOnMap(friend: FriendProfile) {
        val cachedPoints = cachedFriendLocations[friend.userId].orEmpty()
        hideSection()
        bottomNavigation.selectedItemId = R.id.navMap
        if (cachedPoints.isNotEmpty()) {
            drawFriendPoints(cachedFriendPointPairs())
            centerFriendOnMap(friend, cachedPoints.last())
        }

        backgroundExecutor.execute {
            runCatching {
                supabaseClient.fetchFriendLocations(supabaseClient.activeSession(), friend.userId)
            }.onSuccess { points ->
                runOnUiThread {
                    cachedFriendLocations = cachedFriendLocations.toMutableMap().apply {
                        put(friend.userId, points)
                    }
                    drawFriendPoints(cachedFriendPointPairs())
                    val latest = points.lastOrNull()
                    if (latest == null) {
                        if (cachedPoints.isEmpty()) {
                            showSnackbar(getString(R.string.no_friend_locations), isLong = true)
                        }
                    } else if (cachedPoints.isEmpty() || latest.recordedAtMillis > cachedPoints.last().recordedAtMillis) {
                        centerFriendOnMap(friend, latest)
                    }
                }
            }.onFailure { error ->
                runOnUiThread {
                    if (cachedPoints.isEmpty()) {
                        showSnackbar(getString(R.string.sync_failed, userMessage(error)), isLong = true)
                    }
                }
            }
        }
    }

    private fun showSection(title: String) {
        clearTrackFocus()
        sectionTitle.text = title
        sectionContent.removeAllViews()
        authRootView = null
        findViewById<MaterialButton>(R.id.closeSectionButton).visibility =
            if (currentSection == Section.AUTH || currentSection == Section.STATISTICS) View.VISIBLE else View.INVISIBLE
        sectionPanel.visibility = View.VISIBLE
        addTrackFab.visibility = View.GONE
        myLocationButton.visibility = View.GONE
        recordingPanel.visibility = View.GONE
    }

    private fun hideSection() {
        sectionPanel.visibility = View.GONE
        currentSection = Section.NONE
        currentAuthStep = AuthStep.NONE
        authRootView = null
        syncRecordingState()
    }

    private fun setSectionContentPadding(horizontalDp: Int) {
        sectionContent.setPadding(dp(horizontalDp), 0, dp(horizontalDp), dp(32))
        sectionContent.gravity = Gravity.NO_GRAVITY
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

    private fun trackGroupTitle(title: String): TextView {
        return groupTitle(title).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(10)
            }
        }
    }

    private fun searchField(
        hint: String,
        includeCalendar: Boolean,
        initialValue: String,
        onCalendarClick: ((TextInputEditText) -> Unit)? = null,
        onQueryChanged: (String) -> Unit
    ): Pair<View, TextInputEditText> {
        val input = TextInputEditText(this).apply {
            setText(initialValue)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            tag = SEARCH_INPUT_TAG
            setOnEditorActionListener { _, actionId, _ ->
                actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
            }
            setOnKeyListener { _, keyCode, event ->
                keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            }
        }
        val layout = TextInputLayout(this).apply {
            this.hint = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setStartIconDrawable(R.drawable.ic_search)
            endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
            addView(input)
        }
        val root: View = if (includeCalendar) {
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = dp(20)
                    topMargin = dp(12)
                    rightMargin = dp(20)
                    bottomMargin = dp(8)
                }
                addView(layout, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(MaterialButton(this@MainActivity).apply {
                    backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, android.R.color.transparent)
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_transparent)
                    elevation = 0f
                    stateListAnimator = null
                    minWidth = 0
                    minimumWidth = 0
                    minimumHeight = dp(56)
                    setPadding(0, 0, 0, 0)
                    setIconResource(R.drawable.ic_calendar)
                    iconTint = ContextCompat.getColorStateList(this@MainActivity, R.color.icu_purple_ink)
                    iconPadding = 0
                    contentDescription = getString(R.string.pick_track_date)
                    setOnClickListener { onCalendarClick?.invoke(input) }
                    layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                        leftMargin = dp(8)
                    }
                })
            }
        } else {
            layout.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = dp(20)
                    topMargin = dp(12)
                    rightMargin = dp(20)
                    bottomMargin = dp(8)
                }
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onQueryChanged(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        return root to input
    }

    private fun trackCard(track: RecordedTrack): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_track_cell)
            isClickable = true
            isFocusable = true
            setOnClickListener { showTrackOnMap(track) }
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
            setOnClickListener { anchor ->
                anchor.parent.requestDisallowInterceptTouchEvent(true)
                showTrackMenu(anchor, track)
            }
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

    private fun showTrackOnMap(track: RecordedTrack) {
        hideSection()
        bottomNavigation.selectedItemId = R.id.navMap
        if (allSavedTracks.none { it.file.name == track.file.name }) {
            allSavedTracks = (allSavedTracks + track).sortedByDescending { it.startedAtMillis }
        }
        focusTrack(track)
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
                        if (focusedTrackFileName == track.file.name) clearTrackFocus()
                        val updated = trackStore.setTrackVisibility(track, !track.visible)
                        replaceCachedTrack(updated)
                        applySavedTrackOverlays(savedTracksForMap())
                        skippedTrackRefreshes = 2
                        syncTracksSilently()
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
                val updated = trackStore.renameTrack(track, input.text.toString().trim())
                replaceCachedTrack(updated)
                applySavedTrackOverlays(savedTracksForMap())
                skippedTrackRefreshes = 2
                syncTracksSilently()
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
                if (focusedTrackFileName == track.file.name) clearTrackFocus()
                syncManager.markDeleted(track)
                trackStore.deleteTrack(track)
                allSavedTracks = allSavedTracks.filterNot { it.file.name == track.file.name }
                applySavedTrackOverlays(savedTracksForMap())
                skippedTrackRefreshes = 2
                syncTracksSilently()
                showTracksScreen()
            }
            .show()
    }

    private fun replaceCachedTrack(track: RecordedTrack) {
        allSavedTracks = (allSavedTracks.filterNot { it.file.name == track.file.name } + track)
            .sortedByDescending { it.startedAtMillis }
        hasLoadedSavedTracks = true
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
                showInfoTooltip(
                    anchor = this,
                    title = getString(R.string.high_accuracy),
                    body = getString(R.string.high_accuracy_tooltip)
                )
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

    private fun showInfoTooltip(anchor: View, title: String, body: String) {
        infoTooltip?.dismiss()

        val titleView = TextView(this).apply {
            text = title
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        val bodyView = TextView(this).apply {
            text = body
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_secondary))
            textSize = 14f
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.rgb(243, 237, 247))
                cornerRadius = dp(16).toFloat()
            }
            elevation = dp(6).toFloat()
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(titleView)
            addView(bodyView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
        }

        val root = findViewById<View>(R.id.mainContent)
        val width = (resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(320))
        val popup = PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
        }
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val anchorOnScreen = IntArray(2)
        anchor.getLocationOnScreen(anchorOnScreen)
        val minX = dp(12)
        val maxX = resources.displayMetrics.widthPixels - width - dp(12)
        val x = (anchorOnScreen[0] + anchor.width - width).coerceIn(minX, maxX.coerceAtLeast(minX))
        val preferredY = anchorOnScreen[1] - content.measuredHeight - dp(10)
        val y = if (preferredY >= dp(12)) preferredY else anchorOnScreen[1] + anchor.height + dp(10)
        popup.showAtLocation(root, Gravity.NO_GRAVITY, x, y)
        infoTooltip = popup
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
        val tracksByDate = tracks.groupBy { trackLocalDate(it) }
        val activeDates = tracksByDate.keys
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
                val dayTracks = tracksByDate[date].orEmpty()
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
                    if (dayTracks.isNotEmpty()) {
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            showActivityDayTooltip(this, date, dayTracks)
                        }
                    }
                    layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f)
                })
            }
            card.addView(row)
        }
        return card
    }

    private fun showActivityDayTooltip(anchor: View, date: LocalDate, tracks: List<RecordedTrack>) {
        infoTooltip?.dismiss()

        val sortedTracks = tracks.sortedBy { it.startedAtMillis }
        val title = TextView(this).apply {
            text = "${formatCalendarDate(date)} — ${formatCalendarDistance(sortedTracks.sumOf { it.distanceMeters.toDouble() }.toFloat())}"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        val body = TextView(this).apply {
            text = sortedTracks.mapIndexed { index, track ->
                "${index + 1}) ${track.name}, ${formatCalendarDistance(track.distanceMeters)}, ${formatTrackStartTime(track.startedAtMillis)}"
            }.joinToString("\n")
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_secondary))
            textSize = 14f
            setLineSpacing(dp(3).toFloat(), 1.0f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.rgb(243, 237, 247))
                cornerRadius = dp(16).toFloat()
            }
            elevation = dp(6).toFloat()
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(title)
            addView(body, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
        }

        val root = findViewById<View>(R.id.mainContent)
        val width = (resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(340))
        val popup = PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
        }
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val anchorOnScreen = IntArray(2)
        anchor.getLocationOnScreen(anchorOnScreen)
        val minX = dp(12)
        val maxX = resources.displayMetrics.widthPixels - width - dp(12)
        val x = (anchorOnScreen[0] + anchor.width / 2 - width / 2).coerceIn(minX, maxX.coerceAtLeast(minX))
        val preferredY = anchorOnScreen[1] - content.measuredHeight - dp(10)
        val y = if (preferredY >= dp(12)) preferredY else anchorOnScreen[1] + anchor.height + dp(10)
        popup.showAtLocation(root, Gravity.NO_GRAVITY, x, y)
        infoTooltip = popup
    }

    private fun trackLocalDate(track: RecordedTrack): LocalDate {
        return Instant.ofEpochMilli(track.startedAtMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    }

    private fun formatCalendarDate(date: LocalDate): String {
        return date.format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru-RU")))
    }

    private fun formatCalendarDistance(distanceMeters: Float): String {
        return String.format(Locale.forLanguageTag("ru-RU"), "%.1f км", distanceMeters / 1000f)
    }

    private fun formatTrackStartTime(timeMillis: Long): String {
        return DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("ru-RU"))
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timeMillis))
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

    private fun profileStatusCard(
        textValue: String,
        subtitle: String? = null,
        onEdit: (() -> Unit)? = null,
        onSignOut: (() -> Unit)? = null
    ): View {
        return contentCard().apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@MainActivity).apply {
                        text = textValue
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_primary))
                        textSize = 17f
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    subtitle?.takeIf { it.isNotBlank() }?.let { value ->
                        addView(TextView(this@MainActivity).apply {
                            text = value
                            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_secondary))
                            textSize = 14f
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { topMargin = dp(2) }
                        })
                    }
                })

                if (onEdit != null || onSignOut != null) {
                    addView(MaterialButton(this@MainActivity).apply {
                        backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, android.R.color.transparent)
                        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_transparent)
                        elevation = 0f
                        stateListAnimator = null
                        minWidth = 0
                        minimumWidth = 0
                        minimumHeight = dp(40)
                        setPadding(0, 0, 0, 0)
                        contentDescription = getString(R.string.profile_actions)
                        setIconResource(R.drawable.ic_kebab_vertical)
                        iconTint = ContextCompat.getColorStateList(this@MainActivity, R.color.icu_purple_ink)
                        iconPadding = 0
                        setOnClickListener { anchor -> showProfileMenu(anchor, onEdit, onSignOut) }
                        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                    })
                }
            })
        }
    }

    private fun showProfileMenu(anchor: View, onEdit: (() -> Unit)?, onSignOut: (() -> Unit)?) {
        PopupMenu(this, anchor).apply {
            onEdit?.let { menu.add(0, PROFILE_ACTION_EDIT, 0, R.string.edit_name) }
            onSignOut?.let { menu.add(0, PROFILE_ACTION_SIGN_OUT, 1, R.string.sign_out) }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    PROFILE_ACTION_EDIT -> onEdit?.invoke()
                    PROFILE_ACTION_SIGN_OUT -> onSignOut?.invoke()
                }
                true
            }
            show()
        }
    }

    private fun primaryFullWidthButton(textValue: String, onClick: () -> Unit): View {
        return MaterialButton(this).apply {
            text = textValue
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                bottomMargin = dp(12)
            }
        }
    }

    private fun destructiveGhostButton(textValue: String, onClick: () -> Unit): View {
        return MaterialButton(this).apply {
            text = textValue
            isAllCaps = false
            backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, android.R.color.transparent)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_danger))
            elevation = 0f
            stateListAnimator = null
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                topMargin = dp(20)
                bottomMargin = dp(24)
            }
        }
    }

    private fun friendCard(friend: FriendProfile, highlighted: Boolean = false): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (highlighted) {
                background = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(this@MainActivity, R.color.icu_purple_surface))
                    cornerRadius = dp(8).toFloat()
                }
                alpha = 0.55f
                animate().alpha(1f).setDuration(260).start()
            } else {
                setBackgroundResource(R.drawable.bg_track_cell)
            }
            setPadding(dp(14), dp(10), dp(6), dp(10))
            setOnClickListener { showFriendOnMap(friend) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6)
            }
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = friend.displayName
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            })
            if (friend.displayName != friend.email && friend.email.isNotBlank()) {
                addView(TextView(this@MainActivity).apply {
                    text = friend.email
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_secondary))
                    textSize = 13f
                })
            }
            addView(TextView(this@MainActivity).apply {
                text = if (friend.iShare) getString(R.string.sharing_location_enabled) else getString(R.string.sharing_location_disabled)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_secondary))
                textSize = 13f
            })
        })
        card.addView(MaterialButton(this).apply {
            backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, android.R.color.transparent)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_transparent)
            elevation = 0f
            stateListAnimator = null
            minWidth = 0
            minimumWidth = 0
            minimumHeight = dp(40)
            setPadding(0, 0, 0, 0)
            setIconResource(R.drawable.ic_kebab_vertical)
            iconTint = ContextCompat.getColorStateList(this@MainActivity, R.color.icu_purple_ink)
            iconPadding = 0
            setOnClickListener { anchor -> showFriendMenu(anchor, friend) }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        })
        return card
    }

    private fun showFriendMenu(anchor: View, friend: FriendProfile) {
        PopupMenu(this, anchor).apply {
            menu.add(0, FRIEND_ACTION_RENAME, 0, R.string.edit_friend_name)
            if (friend.hasAlias) {
                menu.add(0, FRIEND_ACTION_RESET_NAME, 1, R.string.reset_friend_name)
            }
            menu.add(0, FRIEND_ACTION_SHARE, 2, if (friend.iShare) R.string.hide_myself else R.string.share_location)
            menu.add(0, FRIEND_ACTION_DELETE, 3, R.string.delete_friend)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    FRIEND_ACTION_RENAME -> showEditFriendNameDialog(friend)
                    FRIEND_ACTION_RESET_NAME -> resetFriendAlias(friend)
                    FRIEND_ACTION_SHARE -> setFriendShare(friend, !friend.iShare)
                    FRIEND_ACTION_DELETE -> deleteFriend(friend)
                }
                true
            }
            show()
        }
    }

    private fun setFriendShare(friend: FriendProfile, isSharing: Boolean) {
        backgroundExecutor.execute {
            runCatching {
                supabaseClient.setFriendShare(supabaseClient.activeSession(), friend.friendshipId, isSharing)
            }.onSuccess {
                runOnUiThread {
                    invalidateProfileCache()
                    showProfileScreen()
                    refreshFriendLocationsOnMap()
                }
            }
        }
    }

    private fun setFriendAlias(friend: FriendProfile, firstName: String, lastName: String) {
        backgroundExecutor.execute {
            runCatching {
                supabaseClient.setFriendAlias(supabaseClient.activeSession(), friend.userId, firstName, lastName)
            }.onSuccess {
                runOnUiThread {
                    invalidateProfileCache()
                    showProfileScreen()
                    refreshFriendLocationsOnMap()
                }
            }.onFailure { error ->
                runOnUiThread {
                    showSnackbar(getString(R.string.sync_failed, userMessage(error)), isLong = true)
                }
            }
        }
    }

    private fun resetFriendAlias(friend: FriendProfile) {
        backgroundExecutor.execute {
            runCatching {
                supabaseClient.resetFriendAlias(supabaseClient.activeSession(), friend.userId)
            }.onSuccess {
                runOnUiThread {
                    invalidateProfileCache()
                    showProfileScreen()
                    refreshFriendLocationsOnMap()
                }
            }.onFailure { error ->
                runOnUiThread {
                    showSnackbar(getString(R.string.sync_failed, userMessage(error)), isLong = true)
                }
            }
        }
    }

    private fun deleteFriend(friend: FriendProfile) {
        backgroundExecutor.execute {
            runCatching {
                supabaseClient.deleteFriend(supabaseClient.activeSession(), friend.friendshipId)
            }.onSuccess {
                runOnUiThread {
                    invalidateProfileCache()
                    cachedFriendLocations = cachedFriendLocations.toMutableMap().apply { remove(friend.userId) }
                    showProfileScreen()
                    refreshFriendLocationsOnMap()
                }
            }
        }
    }

    private fun refreshFriendLocationsOnMap() {
        if (friendLocationRefreshInFlight) return
        val session = sessionStore.current()
        if (session == null) {
            clearFriendLocationOverlays()
            return
        }

        friendLocationRefreshInFlight = true
        backgroundExecutor.execute {
            runCatching {
                val activeSession = supabaseClient.activeSession()
                val friends = supabaseClient.fetchFriends(activeSession)
                friends.map { friend ->
                    friend to if (friend.friendShares) {
                        supabaseClient.fetchFriendLocations(activeSession, friend.userId)
                    } else {
                        emptyList()
                    }
                }
            }.onSuccess { friendPoints ->
                runOnUiThread {
                    friendLocationRefreshInFlight = false
                    cachedFriends = friendPoints.map { it.first }
                    updateCachedFriendLocations(friendPoints)
                    drawFriendPoints(friendPoints)
                }
            }.onFailure {
                runOnUiThread {
                    friendLocationRefreshInFlight = false
                }
            }
        }
    }

    private fun refreshFriendLocationsForFriends(friends: List<FriendProfile>) {
        backgroundExecutor.execute {
            runCatching {
                val activeSession = supabaseClient.activeSession()
                friends.map { friend ->
                    friend to if (friend.friendShares) {
                        supabaseClient.fetchFriendLocations(activeSession, friend.userId)
                    } else {
                        emptyList()
                    }
                }
            }.onSuccess { friendPoints ->
                runOnUiThread {
                    updateCachedFriendLocations(friendPoints)
                    drawFriendPoints(friendPoints)
                }
            }
        }
    }

    private fun updateCachedFriendLocations(friendPoints: List<Pair<FriendProfile, List<LocationSharePoint>>>) {
        cachedFriendLocations = cachedFriendLocations.toMutableMap().apply {
            friendPoints.forEach { (friend, points) -> put(friend.userId, points) }
        }
    }

    private fun cachedFriendPointPairs(): List<Pair<FriendProfile, List<LocationSharePoint>>> {
        return cachedFriends.map { friend -> friend to cachedFriendLocations[friend.userId].orEmpty() }
    }

    private fun drawFriendPoints(friendPoints: List<Pair<FriendProfile, List<LocationSharePoint>>>) {
        friendLocationOverlays.forEach { map.overlays.remove(it) }
        friendLocationOverlays.clear()
        friendTooltip?.dismiss()
        friendPoints.forEach { (friend, points) ->
            if (points.isNotEmpty()) {
                drawFriendLocation(friend, points, centerOnLastPoint = false, clearExisting = false)
            }
        }
        map.invalidate()
    }

    private fun drawFriendLocation(
        friend: FriendProfile,
        points: List<LocationSharePoint>,
        centerOnLastPoint: Boolean,
        clearExisting: Boolean
    ) {
        if (clearExisting) clearFriendLocationOverlays()
        if (points.isEmpty()) {
            showSnackbar(getString(R.string.no_friend_locations), isLong = true)
            return
        }
        val color = friendColor(friend)
        val polyline = Polyline(map).apply {
            outlinePaint.color = color
            outlinePaint.alpha = 110
            outlinePaint.strokeWidth = dp(3).toFloat()
            outlinePaint.isAntiAlias = true
            setPoints(points.map { GeoPoint(it.latitude, it.longitude) })
        }
        val last = points.last()
        val lastPoint = GeoPoint(last.latitude, last.longitude)
        val marker = Marker(map).apply {
            position = lastPoint
            title = friend.displayName
            icon = BitmapDrawable(resources, createFriendMarkerIcon(friend, color))
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setOnMarkerClickListener { _, _ ->
                showFriendTooltip(friend, last, lastPoint)
                true
            }
        }
        friendLocationOverlays.add(polyline)
        friendLocationOverlays.add(marker)
        map.overlays.add(polyline)
        map.overlays.add(marker)
        if (centerOnLastPoint) {
            centerFriendOnMap(friend, last)
        }
        map.invalidate()
    }

    private fun centerFriendOnMap(friend: FriendProfile, point: LocationSharePoint) {
        val geoPoint = GeoPoint(point.latitude, point.longitude)
        friendTooltip?.dismiss()
        map.controller.animateTo(geoPoint, 17.0, FRIEND_MAP_ANIMATION_MS)
        elapsedHandler.postDelayed({
            if (bottomNavigation.selectedItemId == R.id.navMap && sectionPanel.visibility != View.VISIBLE) {
                showFriendTooltip(friend, point, geoPoint)
            }
        }, FRIEND_MAP_ANIMATION_MS + FRIEND_TOOLTIP_DELAY_MS)
    }

    private fun clearFriendLocationOverlays() {
        friendTooltip?.dismiss()
        friendLocationOverlays.forEach { map.overlays.remove(it) }
        friendLocationOverlays.clear()
        map.invalidate()
    }

    private fun createSelfLocationIcon(): Bitmap {
        val size = dp(12).coerceAtLeast(12)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val center = size / 2f
        paint.color = Color.BLACK
        canvas.drawCircle(center, center, center, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(center, center, dp(1).coerceAtLeast(1).toFloat(), paint)
        return bitmap
    }

    private fun createFriendMarkerIcon(friend: FriendProfile, fillColor: Int): Bitmap {
        val size = dp(24).coerceAtLeast(24)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val center = size / 2f

        paint.style = Paint.Style.FILL
        paint.color = fillColor
        canvas.drawCircle(center, center, center - dp(0.5f), paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(0.5f)
        paint.color = Color.argb(180, 0, 0, 0)
        canvas.drawCircle(center, center, center - paint.strokeWidth / 2f, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = dp(11).toFloat()
        paint.textAlign = Paint.Align.CENTER
        val initials = initialsForFriend(friend)
        val bounds = Rect()
        paint.getTextBounds(initials, 0, initials.length, bounds)
        canvas.drawText(initials, center, center - bounds.exactCenterY(), paint)
        return bitmap
    }

    private fun initialsForFriend(friend: FriendProfile): String {
        val first = friend.displayFirstName.trim().firstOrNull()?.uppercaseChar()
        val last = friend.displayLastName.trim().firstOrNull()?.uppercaseChar()
        val fromName = listOfNotNull(first, last).joinToString("")
        if (fromName.length >= 2) return fromName.take(2)

        val emailPrefix = friend.email.substringBefore("@").filter { it.isLetterOrDigit() }
        return emailPrefix.take(2).uppercase(Locale.getDefault()).ifBlank { "??" }
    }

    private fun friendColor(friend: FriendProfile): Int {
        val colors = intArrayOf(
            Color.rgb(205, 234, 142),
            Color.rgb(244, 190, 124),
            Color.rgb(244, 158, 124),
            Color.rgb(238, 132, 156),
            Color.rgb(218, 140, 206),
            Color.rgb(186, 151, 232),
            Color.rgb(148, 172, 238),
            Color.rgb(126, 196, 234),
            Color.rgb(119, 218, 213),
            Color.rgb(120, 219, 170),
            Color.rgb(149, 219, 121),
            Color.rgb(196, 218, 118),
            Color.rgb(234, 211, 119),
            Color.rgb(235, 174, 119),
            Color.rgb(235, 137, 128),
            Color.rgb(209, 140, 184),
            Color.rgb(158, 150, 227)
        )
        return colors[kotlin.math.abs(friend.userId.hashCode()) % colors.size]
    }

    private fun showFriendTooltip(friend: FriendProfile, point: LocationSharePoint, geoPoint: GeoPoint) {
        friendTooltip?.dismiss()

        val title = TextView(this).apply {
            text = "${friend.displayName} (${distanceToFriend(point)})"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        val body = TextView(this).apply {
            text = formatLocationUpdatedAt(point.recordedAtMillis)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icu_text_secondary))
            textSize = 14f
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.rgb(243, 237, 247))
                cornerRadius = dp(16).toFloat()
            }
            elevation = dp(6).toFloat()
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(title)
            addView(body, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) })
        }

        val width = dp(260)
        val popup = PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
        }
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val pointOnMap = Point()
        map.projection.toPixels(geoPoint, pointOnMap)
        val mapOnScreen = IntArray(2)
        map.getLocationOnScreen(mapOnScreen)
        val minX = mapOnScreen[0] + dp(12)
        val maxX = mapOnScreen[0] + map.width - width - dp(12)
        val minY = mapOnScreen[1] + dp(12)
        val x = (mapOnScreen[0] + pointOnMap.x - width / 2).coerceIn(minX, maxX.coerceAtLeast(minX))
        val y = (mapOnScreen[1] + pointOnMap.y - content.measuredHeight - dp(14)).coerceAtLeast(minY)
        popup.showAtLocation(map, Gravity.NO_GRAVITY, x, y)
        friendTooltip = popup
    }

    private fun distanceToFriend(point: LocationSharePoint): String {
        val current = lastKnownUserLocation ?: locationOverlay?.myLocation?.let {
            Location("map").apply {
                latitude = it.latitude
                longitude = it.longitude
            }
        } ?: return "—"
        val result = FloatArray(1)
        Location.distanceBetween(
            current.latitude,
            current.longitude,
            point.latitude,
            point.longitude,
            result
        )
        return if (result[0] < 1000f) {
            "${result[0].toInt()} м"
        } else {
            String.format(Locale.forLanguageTag("ru-RU"), "%.1f км", result[0] / 1000f)
        }
    }

    private fun distanceToPoint(point: GeoPoint): String {
        val current = lastKnownUserLocation ?: locationOverlay?.myLocation?.let {
            Location("map").apply {
                latitude = it.latitude
                longitude = it.longitude
            }
        } ?: return "-"
        val result = FloatArray(1)
        Location.distanceBetween(
            current.latitude,
            current.longitude,
            point.latitude,
            point.longitude,
            result
        )
        return formatMapDistance(result[0])
    }

    private fun formatMapDistance(meters: Float): String {
        return if (meters < 1000f) {
            "${meters.toInt()} м"
        } else {
            String.format(Locale.forLanguageTag("ru-RU"), "%.1f км", meters / 1000f)
        }
    }

    private fun formatLocationUpdatedAt(timeMillis: Long): String {
        val instant = Instant.ofEpochMilli(timeMillis)
        val zone = ZoneId.systemDefault()
        val date = instant.atZone(zone).toLocalDate()
        val time = DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("ru-RU")).format(instant.atZone(zone))
        return if (date == LocalDate.now(zone)) {
            getString(R.string.updated_at_time, time)
        } else {
            val formattedDate = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru-RU")).format(instant.atZone(zone))
            getString(R.string.updated_at_date_time, formattedDate, time)
        }
    }

    private fun registerRecordingReceiver() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(TrackRecordingService.ACTION_STATE_CHANGED)
            addAction(LocationBroadcastService.ACTION_STATE_CHANGED)
        }
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
                val selfIcon = createSelfLocationIcon()
                overlay.setPersonIcon(selfIcon)
                overlay.setDirectionArrow(selfIcon, selfIcon)
                overlay.setPersonAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                overlay.setDirectionAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
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

    private fun requestForegroundLiveLocationUpdates() {
        if (!hasLocationPermission() || sessionStore.current() == null) return
        val provider = when {
            appLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            appLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return
        }
        try {
            appLocationManager.requestLocationUpdates(
                provider,
                TrackRecordingService.LIVE_LOCATION_INTERVAL_MS,
                0f,
                foregroundLocationListener
            )
            appLocationManager.getLastKnownLocation(provider)
                ?.takeIf { System.currentTimeMillis() - it.time <= 120_000L }
                ?.let { uploadForegroundLiveLocation(it) }
        } catch (_: SecurityException) {
            return
        }
    }

    private fun stopForegroundLiveLocationUpdates() {
        appLocationManager.removeUpdates(foregroundLocationListener)
    }

    private fun uploadForegroundLiveLocation(location: Location) {
        if (TrackRecordingService.currentState.isRecording) return
        val now = System.currentTimeMillis()
        if (now - lastForegroundLiveUploadMillis < TrackRecordingService.LIVE_LOCATION_INTERVAL_MS) return
        lastForegroundLiveUploadMillis = now
        backgroundExecutor.execute {
            liveLocationUploader.enqueueAndFlush(location)
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

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }

    private data class StatsPage(
        val title: String,
        val type: TrackType?,
        val accentColor: Int
    )

    private data class TrackListPage(
        val title: String,
        val type: TrackType
    )

    private data class TrackHit(
        val track: RecordedTrack,
        val distancePx: Double
    )

    private enum class Section {
        NONE,
        TRACKS,
        STATISTICS,
        POINTS,
        SETTINGS,
        PROFILE,
        AUTH
    }

    private enum class AuthStep {
        NONE,
        EMAIL,
        PASSWORD,
        MESSAGE
    }

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

    private inner class SavedPointAdapter(
        private val points: MutableList<SavedPoint>
    ) : RecyclerView.Adapter<SavedPointHolder>() {
        var dragStarter: ((SavedPointHolder) -> Unit)? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SavedPointHolder {
            val container = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            return SavedPointHolder(container)
        }

        override fun onBindViewHolder(holder: SavedPointHolder, position: Int) {
            holder.container.removeAllViews()
            val card = savedPointCard(points[position]).apply {
                setOnLongClickListener {
                    dragStarter?.invoke(holder)
                    dragStarter != null
                }
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10)
                }
            }
            holder.container.addView(card)
        }

        override fun getItemCount(): Int = points.size

        fun move(from: Int, to: Int): Boolean {
            if (from !in points.indices || to !in points.indices) return false
            val item = points.removeAt(from)
            points.add(to, item)
            notifyItemMoved(from, to)
            return true
        }

        fun currentPoints(): List<SavedPoint> = points.toList()
    }

    private inner class SavedPointDragCallback(
        private val adapter: SavedPointAdapter
    ) : ItemTouchHelper.Callback() {
        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            return adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun isLongPressDragEnabled(): Boolean = false

        override fun onChildDraw(
            c: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            if (actionState != ItemTouchHelper.ACTION_STATE_DRAG || !isCurrentlyActive) return

            val zonePx = dp(DRAG_SCROLL_ZONE_DP).toFloat()
            val top = viewHolder.itemView.top + dY
            val bottom = viewHolder.itemView.bottom + dY
            val scrollY = when {
                top < zonePx -> -dragScrollStep(zonePx - top, zonePx)
                bottom > recyclerView.height - zonePx -> dragScrollStep(bottom - (recyclerView.height - zonePx), zonePx)
                else -> 0
            }
            if (scrollY != 0) {
                recyclerView.scrollBy(0, scrollY)
            }
        }

        private fun dragScrollStep(overlapPx: Float, zonePx: Float): Int {
            val ratio = (overlapPx / zonePx).coerceIn(0.2f, 1f)
            return (dp(DRAG_SCROLL_MAX_STEP_DP) * ratio).toInt().coerceAtLeast(2)
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                viewHolder?.itemView?.animate()
                    ?.translationZ(dp(10).toFloat())
                    ?.rotation(1.5f)
                    ?.setDuration(120L)
                    ?.start()
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.animate()
                .translationZ(0f)
                .rotation(0f)
                .setDuration(140L)
                .start()
            savedPointStore.reorderPoints(adapter.currentPoints())
            skippedPointRefreshes = 1
            syncTracksSilently()
            loadSavedPointsOnMap()
        }
    }

    private class StatsPageHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)
    private class SavedPointHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

    companion object {
        private const val TRACK_ACTION_RENAME = 1
        private const val TRACK_ACTION_VISIBILITY = 2
        private const val TRACK_ACTION_DELETE = 3
        private const val POINT_ACTION_RENAME = 4
        private const val POINT_ACTION_VISIBILITY = 5
        private const val POINT_ACTION_DELETE = 6
        private const val FRIEND_ACTION_RENAME = 8
        private const val FRIEND_ACTION_RESET_NAME = 9
        private const val FRIEND_ACTION_SHARE = 10
        private const val FRIEND_ACTION_DELETE = 11
        private const val PROFILE_ACTION_EDIT = 12
        private const val PROFILE_ACTION_SIGN_OUT = 13
        private const val TRACK_STROKE_WIDTH = 8f
        private const val TRACK_FOCUS_STROKE_WIDTH = 12f
        private const val TRACK_FOCUS_HALO_WIDTH = 26f
        private const val TRACK_DIM_ALPHA = 90
        private const val TRACK_FOCUS_VIEW_PADDING_DP = 56
        private const val TRACK_FOCUS_TOOLTIP_DELAY_MS = 420L
        private const val AUTO_TRACK_FOCUS_MS = 10_000L
        private const val TIMER_INTERVAL_MS = 1_000L
        private const val FRIEND_HIGHLIGHT_DURATION_MS = 3_500L
        private const val STARTUP_DEFER_MS = 350L
        private const val SPLASH_VISIBLE_MS = 450L
        private const val SPLASH_DURATION_MS = 850L
        private const val FRIEND_LOCATION_REFRESH_MS = 30_000L
        private const val PROFILE_REFRESH_MS = 60_000L
        private const val FRIEND_MAP_ANIMATION_MS = 450L
        private const val FRIEND_TOOLTIP_DELAY_MS = 80L
        private const val TRACK_SAVED_SNACKBAR_MS = 10_000
        private const val CHANGELOG_ASSET_NAME = "CHANGELOG.md"
        private const val SEARCH_INPUT_TAG = "icu_search_input"
        private const val DRAG_SCROLL_ZONE_DP = 132
        private const val DRAG_SCROLL_MAX_STEP_DP = 24
    }
}
