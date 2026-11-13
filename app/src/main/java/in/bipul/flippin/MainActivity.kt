package `in`.bipul.flippin

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var state: SharedPreferences
    private lateinit var pins: PinStore
    private lateinit var latitude: EditText
    private lateinit var longitude: EditText
    private lateinit var pad: CoordinateMapView
    private lateinit var locationSearch: LocationSearch
    private var searching = false
    private var searchGeneration = 0
    private var online = false
    private var monitoringNetwork = false
    private var defaultNetwork: Network? = null
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateConnectivity {
            defaultNetwork = network
        }
        override fun onLost(network: Network) = updateConnectivity {
            if (defaultNetwork == network) {
                defaultNetwork = null
                renderConnectivity(null)
            }
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = updateConnectivity {
            if (defaultNetwork == network) renderConnectivity(capabilities)
        }
    }
    private var pending: Coordinates? = null
    private var updatingFields = false
    private var selectedPinId: String? = null
    private var setupExpanded = false
    private var imeVisible = false
    private var setupReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val screen = findViewById<View>(R.id.screen)
        screen.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                imeVisible = insets.isVisible(WindowInsets.Type.ime())
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
                @Suppress("DEPRECATION")
                imeVisible = insets.systemWindowInsetBottom > dp(120)
            }
            renderActionBar()
            insets
        }
        state = getSharedPreferences(MockLocationService.STATE, MODE_PRIVATE)
        pins = PinStore(getSharedPreferences("saved_pins", MODE_PRIVATE))
        latitude = findViewById(R.id.latitude)
        longitude = findViewById(R.id.longitude)
        pad = findViewById(R.id.coordinate_pad)
        locationSearch = LocationSearch(Geocoder(this, Locale.ENGLISH))
        findViewById<View>(R.id.search_location).setOnClickListener { searchLocation() }
        findViewById<EditText>(R.id.location_query).setOnFocusChangeListener { _, _ -> renderActionBar() }
        findViewById<EditText>(R.id.location_query).setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                searchLocation()
                true
            } else false
        }
        pad.onTileFailureChanged = { failed ->
            findViewById<View>(R.id.map_retry).visibility = if (failed) View.VISIBLE else View.GONE
        }
        findViewById<Button>(R.id.map_retry).setOnClickListener { pad.retryTiles() }
        findViewById<TextView>(R.id.map_attribution).apply {
            text = SpannableString(getString(R.string.map_attribution)).apply {
                for ((name, url) in listOf(
                    "OpenStreetMap" to "https://www.openstreetmap.org/copyright",
                    "OpenMapTiles" to "https://openmaptiles.org/",
                    "OpenFreeMap" to "https://openfreemap.org/"
                )) {
                    val start = indexOf(name)
                    setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) = openMapAttribution(url)
                    }, start, start + name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            movementMethod = LinkMovementMethod.getInstance()
        }
        findViewById<TextView>(R.id.online_privacy).apply {
            val link = getString(R.string.online_privacy_link)
            text = SpannableString(getString(R.string.online_privacy)).apply {
                val start = lastIndexOf(link)
                if (start >= 0) setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) = showAbout()
                }, start, start + link.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            movementMethod = LinkMovementMethod.getInstance()
        }
        findViewById<View>(R.id.info).setOnClickListener { showAbout() }
        findViewById<View>(R.id.version).setOnClickListener { showAbout() }
        findViewById<View>(R.id.manage_pins).setOnClickListener { managePins() }
        findViewById<View>(R.id.online_map).layoutParams.height = resources.displayMetrics.let {
            (it.heightPixels * 0.36f).toInt().coerceIn(dp(200), dp(280))
        }
        latitude.setText(state.getString(MockLocationService.LATITUDE, ""))
        longitude.setText(state.getString(MockLocationService.LONGITUDE, ""))
        setupExpanded = savedInstanceState?.getBoolean("setup_expanded") ?: false
        selectedPinId = savedInstanceState?.getString("selected_pin")
        pending = savedInstanceState?.let {
            Coordinates.parse(it.getString("pending_lat").orEmpty(), it.getString("pending_lon").orEmpty())
        }
        findViewById<Button>(R.id.start).setOnClickListener { readCoordinates()?.let(::prepareStart) }
        findViewById<Button>(R.id.stop).setOnClickListener {
            stopService(Intent(this, MockLocationService::class.java))
        }
        findViewById<View>(R.id.save_pin).setOnClickListener { readCoordinates()?.let { savePin(it) } }
        findViewById<Button>(R.id.developer_options).setOnClickListener {
            openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        }
        findViewById<Button>(R.id.location_settings).setOnClickListener {
            openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        }
        findViewById<Button>(R.id.setup_toggle).setOnClickListener {
            setSetupExpanded(!setupExpanded)
        }
        pad.onCoordinateChanged = { point -> setCoordinateInputs(point, recenterPad = false) }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable?) {
                if (!updatingFields) {
                    selectedPinId = null
                    updatePreview()
                }
            }
        }
        latitude.addTextChangedListener(watcher)
        longitude.addTextChangedListener(watcher)
        val initialPace = Pace.entries.firstOrNull { it.name == savedInstanceState?.getString("pace") }
            ?: Pace.WALK
        for ((id, pace) in paceButtons()) {
            findViewById<Button>(id).setOnClickListener { selectPace(pace, focusMap = true) }
        }
        selectPace(initialPace, focusMap = false)
        findViewById<Button>(R.id.nudge_north).setOnClickListener { nudge(1, 0) }
        findViewById<Button>(R.id.nudge_south).setOnClickListener { nudge(-1, 0) }
        findViewById<Button>(R.id.nudge_west).setOnClickListener { nudge(0, -1) }
        findViewById<Button>(R.id.nudge_east).setOnClickListener { nudge(0, 1) }
        findViewById<ImageButton>(R.id.copy_coordinates).setOnClickListener {
            readCoordinates()?.let { point ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText(getString(R.string.app_name), "${point.latitude}, ${point.longitude}")
                )
                Toast.makeText(this, R.string.coordinates_copied, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<TextView>(R.id.version).text = getString(R.string.version, BuildConfig.VERSION_NAME)
        renderPins()
        updatePreview()
        setSetupExpanded(setupExpanded)
    }

    override fun onStart() {
        super.onStart()
        state.registerOnSharedPreferenceChangeListener(this)
        monitoringNetwork = true
        defaultNetwork = connectivity.activeNetwork
        renderConnectivity(connectivity.getNetworkCapabilities(defaultNetwork))
        connectivity.registerDefaultNetworkCallback(networkCallback)
        renderState()
    }

    override fun onResume() {
        super.onResume()
        pad.resumeMap()
        renderSetup()
    }

    override fun onPause() {
        pad.pauseMap()
        super.onPause()
    }

    override fun onDestroy() {
        locationSearch.close()
        pad.destroyMap()
        super.onDestroy()
    }

    override fun onStop() {
        monitoringNetwork = false
        connectivity.unregisterNetworkCallback(networkCallback)
        state.unregisterOnSharedPreferenceChangeListener(this)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pace", pad.pace.name)
        outState.putString("selected_pin", selectedPinId)
        outState.putBoolean("setup_expanded", setupExpanded)
        pad.saveCamera(outState)
        pending?.let {
            outState.putString("pending_lat", it.latitude.toString())
            outState.putString("pending_lon", it.longitude.toString())
        }
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        selectedPinId = savedInstanceState.getString("selected_pin")
        updatePreview(recenterPad = false)
        pad.restoreCamera(savedInstanceState)
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences, key: String?) = renderState()

    private fun searchLocation() {
        if (!online || searching) return
        val input = findViewById<EditText>(R.id.location_query)
        val query = input.text.toString().trim()
        input.error = null
        if (query.isBlank()) {
            input.error = getString(R.string.search_required)
            input.requestFocus()
            return
        }
        findViewById<LinearLayout>(R.id.search_results).removeAllViews()
        if (!Geocoder.isPresent()) {
            showSearchStatus(getString(R.string.search_unavailable), failed = true)
            return
        }
        searching = true
        val request = ++searchGeneration
        input.isEnabled = false
        findViewById<View>(R.id.search_location).isEnabled = false
        showSearchStatus(getString(R.string.search_loading))
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
        locationSearch.search(query) { result ->
            runOnUiThread {
                if (!isDestroyed && !isFinishing && online && request == searchGeneration) {
                    searching = false
                    input.isEnabled = true
                    findViewById<View>(R.id.search_location).isEnabled = true
                    result.fold(
                        onSuccess = { places -> renderSearchResults(places) },
                        onFailure = { error ->
                            Log.w(TAG, "Location search failed", error)
                            showSearchStatus(getString(R.string.search_failed), failed = true)
                        }
                    )
                }
            }
        }
    }

    private fun updateConnectivity(update: () -> Unit) = runOnUiThread {
        if (monitoringNetwork && !isDestroyed) update()
    }

    private fun renderConnectivity(capabilities: NetworkCapabilities?) {
        val available = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (online == available) return
        if (online && !available) {
            searchGeneration++
            searching = false
            findViewById<EditText>(R.id.location_query).apply {
                isEnabled = true
                if (hasFocus()) {
                    getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
                }
                clearFocus()
            }
            findViewById<View>(R.id.search_location).isEnabled = true
            findViewById<View>(R.id.search_status).visibility = View.GONE
            findViewById<LinearLayout>(R.id.search_results).removeAllViews()
        }
        online = available
        val visibility = if (online) View.VISIBLE else View.GONE
        findViewById<View>(R.id.online_search).visibility = visibility
        findViewById<View>(R.id.online_map).visibility = visibility
        findViewById<TextView>(R.id.pad_hint).apply {
            setText(if (online) R.string.pad_hint else R.string.offline_hint)
            if (online) {
                background = null
                setPadding(0, 0, 0, 0)
                textSize = 12f
                setTextColor(getColor(R.color.muted))
            } else {
                setBackgroundResource(R.drawable.soft_button)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                textSize = 13f
                setTextColor(getColor(R.color.text))
            }
        }
        pad.setOnline(online)
        updatePreview(recenterPad = false)
    }

    private fun showSearchStatus(message: String, failed: Boolean = false) {
        findViewById<TextView>(R.id.search_status).apply {
            text = message
            visibility = View.VISIBLE
            setTextColor(getColor(if (failed) R.color.error else R.color.muted))
        }
    }

    private fun renderSearchResults(places: List<PlaceResult>) {
        showSearchStatus(getString(if (places.isEmpty()) R.string.search_empty else R.string.search_choose))
        val results = findViewById<LinearLayout>(R.id.search_results)
        places.forEach { place ->
            results.addView(TextView(this).apply {
                text = place.name
                textSize = 14f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(getColor(R.color.text))
                setBackgroundResource(R.drawable.pin_card)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                minimumHeight = dp(48)
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
                setOnClickListener {
                    setCoordinateInputs(place.coordinates)
                    pad.zoomTo(CoordinateMapView.AREA_ZOOM)
                    results.removeAllViews()
                    showSearchStatus(getString(R.string.search_selected, place.name))
                    this@MainActivity.findViewById<EditText>(R.id.location_query).clearFocus()
                    this@MainActivity.findViewById<ScrollView>(R.id.content_scroll)
                        .smoothScrollTo(0, this@MainActivity.findViewById<View>(R.id.online_map).top)
                }
            })
        }
    }

    private fun readCoordinates(): Coordinates? {
        latitude.error = null
        longitude.error = null
        val lat = Coordinates.decimal(latitude.text.toString())
        val lon = Coordinates.decimal(longitude.text.toString())
        if (lat == null || lat !in -90.0..90.0) {
            latitude.error = getString(R.string.invalid_latitude)
            latitude.requestFocus()
            return null
        }
        if (lon == null || lon !in -180.0..180.0) {
            longitude.error = getString(R.string.invalid_longitude)
            longitude.requestFocus()
            return null
        }
        return Coordinates(lat, lon)
    }

    private fun mockAllowed(): Boolean =
        getSystemService(AppOpsManager::class.java).checkOpNoThrow(
            AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), packageName
        ) == AppOpsManager.MODE_ALLOWED

    private fun locationEnabled(): Boolean {
        val manager = getSystemService(LocationManager::class.java)
        return if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled
        else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun prepareStart(point: Coordinates) {
        if (!mockAllowed()) {
            showError(getString(R.string.mock_not_ready))
            setSetupExpanded(true)
            findViewById<ScrollView>(R.id.content_scroll).smoothScrollTo(0, 0)
            return
        }
        if (!locationEnabled()) {
            showError(getString(R.string.location_off))
            setSetupExpanded(true)
            findViewById<ScrollView>(R.id.content_scroll).smoothScrollTo(0, 0)
            return
        }
        val permissions = mutableListOf<String>()
        if (!hasLocationPermission()) {
            permissions += Manifest.permission.ACCESS_COARSE_LOCATION
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        if (permissions.isNotEmpty()) {
            pending = point
            requestPermissions(permissions.toTypedArray(), PERMISSIONS)
        } else {
            start(point)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSIONS) return
        val point = pending ?: return
        pending = null
        if (hasLocationPermission()) start(point)
        else showError(getString(R.string.location_permission_required))
        renderSetup()
    }

    private fun start(point: Coordinates) {
        try {
            startForegroundService(Intent(this, MockLocationService::class.java).apply {
                putExtra(MockLocationService.LATITUDE, point.latitude.toString())
                putExtra(MockLocationService.LONGITUDE, point.longitude.toString())
            })
            if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
                Toast.makeText(this, R.string.notifications_disabled, Toast.LENGTH_LONG).show()
            }
        } catch (error: SecurityException) {
            startFailed(error)
        } catch (error: IllegalStateException) {
            startFailed(error)
        }
    }

    private fun startFailed(error: Exception) {
        Log.e(TAG, "Cannot start foreground service", error)
        showError(getString(R.string.start_failed, error.message))
    }

    private fun renderState() {
        val running = MockLocationService.isRunning && state.getBoolean(MockLocationService.RUNNING, false)
        findViewById<TextView>(R.id.status).apply {
            setText(if (running) R.string.active else R.string.idle)
            isActivated = running
            setTextColor(getColor(if (running) R.color.success else R.color.muted))
        }
        val point = Coordinates.parse(
            state.getString(MockLocationService.LATITUDE, "").orEmpty(),
            state.getString(MockLocationService.LONGITUDE, "").orEmpty()
        )
        findViewById<TextView>(R.id.status_detail).apply {
            text = point?.takeIf { running }?.let { getString(R.string.active_coordinates, it.display()) }
            visibility = if (running && point != null) View.VISIBLE else View.GONE
        }
        findViewById<Button>(R.id.stop).apply {
            isEnabled = running
            visibility = if (running) View.VISIBLE else View.GONE
        }
        findViewById<Button>(R.id.start).setText(if (running) R.string.apply else R.string.start)
        findViewById<TextView>(R.id.draft_hint).setText(if (running) R.string.draft_active else R.string.draft_idle)
        showError(state.getString(MockLocationService.ERROR, null))
    }

    private fun renderSetup() {
        val checks = listOf(
            Triple(R.id.check_mock, mockAllowed(), Pair(R.string.check_mock_ready, R.string.check_mock_needed)),
            Triple(R.id.check_location, locationEnabled(), Pair(R.string.check_location_ready, R.string.check_location_needed)),
            Triple(R.id.check_permission, hasLocationPermission(), Pair(R.string.check_permission_ready, R.string.check_permission_needed))
        )
        checks.forEach { (id, ready, labels) ->
            findViewById<TextView>(id).apply {
                setText(if (ready) labels.first else labels.second)
                setTextColor(getColor(if (ready) R.color.success else R.color.muted))
            }
        }
        val ready = checks.count { it.second }
        findViewById<TextView>(R.id.setup_status).text = getString(R.string.setup_progress, ready)
        setupReady = ready == checks.size
        renderSetupCard()
    }

    private fun renderSetupCard() {
        findViewById<View>(R.id.setup_card).visibility =
            if (setupReady && !setupExpanded) View.GONE else View.VISIBLE
    }

    private fun renderActionBar() {
        val searching = imeVisible && findViewById<View>(R.id.location_query).hasFocus()
        findViewById<View>(R.id.action_bar).visibility = if (searching) View.GONE else View.VISIBLE
    }

    private fun showAbout() {
        val message = listOf(
            getString(R.string.intro), getString(R.string.limitations_title) + "\n" + getString(R.string.limitations), getString(R.string.search_privacy),
            getString(R.string.map_privacy), getString(R.string.map_retry_help),
            getString(R.string.version, BuildConfig.VERSION_NAME).substringBefore(" · ")
        ).joinToString("\n\n")
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setMessage(message)
            .setNeutralButton(R.string.setup_open) { _, _ ->
                setSetupExpanded(true)
                findViewById<ScrollView>(R.id.content_scroll).smoothScrollTo(0, 0)
            }
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun setSetupExpanded(expanded: Boolean) {
        setupExpanded = expanded
        findViewById<View>(R.id.setup_details).visibility = if (expanded) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.setup_toggle).apply {
            setText(if (expanded) R.string.setup_collapse else R.string.setup_expand)
            isSelected = expanded
        }
        renderSetupCard()
    }

    private fun showError(message: String?) {
        findViewById<TextView>(R.id.error).apply {
            text = message
            visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
            if (!message.isNullOrEmpty()) {
                post { requestRectangleOnScreen(Rect(0, 0, width, height), false) }
            }
        }
    }

    private fun paceButtons(): List<Pair<Int, Pace>> = listOf(
        R.id.pace_walk to Pace.WALK, R.id.pace_run to Pace.RUN, R.id.pace_drive to Pace.DRIVE
    )

    private fun selectPace(pace: Pace, focusMap: Boolean) {
        pad.setPace(pace, focusMap)
        paceButtons().forEach { (id, value) -> findViewById<Button>(id).isSelected = value == pace }
        findViewById<TextView>(R.id.nudge_step).text = getString(
            R.string.nudge_step, pace.meters, pace.secondsPerTap.roundToInt(), pace.kilometersPerHour
        )
        listOf(
            R.id.nudge_north to R.string.nudge_north_desc, R.id.nudge_south to R.string.nudge_south_desc,
            R.id.nudge_west to R.string.nudge_west_desc, R.id.nudge_east to R.string.nudge_east_desc
        ).forEach { (id, description) -> findViewById<View>(id).contentDescription = getString(description, pace.meters) }
    }

    private fun nudge(north: Int, east: Int) {
        readCoordinates()?.let { point ->
            val meters = pad.pace.meters.toDouble()
            setCoordinateInputs(point.moved(north * meters, east * meters))
            pad.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setCoordinateInputs(point: Coordinates, pinId: String? = null, recenterPad: Boolean = true) {
        updatingFields = true
        try {
            // Editable coordinates remain lossless and locale-independent.
            latitude.setText(point.latitude.toString())
            longitude.setText(point.longitude.toString())
            latitude.error = null
            longitude.error = null
            selectedPinId = pinId
        } finally {
            updatingFields = false
        }
        updatePreview(recenterPad)
    }

    private fun updatePreview(recenterPad: Boolean = true) {
        val point = Coordinates.parse(latitude.text.toString(), longitude.text.toString())
        pad.setCoordinates(point, recenterPad)
        findViewById<View>(R.id.map_limit).visibility =
            if (point != null && point.latitude !in
                -CoordinateMapView.MAP_LATITUDE_LIMIT..CoordinateMapView.MAP_LATITUDE_LIMIT
            ) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.preview).apply {
            typeface = if (point != null) Typeface.MONOSPACE else Typeface.DEFAULT
            text = point?.display() ?: getString(
                if (latitude.text.isEmpty() && longitude.text.isEmpty()) {
                    if (online) R.string.pad_empty else R.string.offline_empty
                } else R.string.pad_invalid
            )
        }
        findViewById<ImageButton>(R.id.copy_coordinates).apply {
            isEnabled = point != null
            alpha = if (isEnabled) 1f else 0.4f
        }
        val container = findViewById<LinearLayout>(R.id.pins)
        for (index in 0 until container.childCount) {
            val row = container.getChildAt(index)
            val pin = row.tag as? SavedPin
            row.isSelected = pin != null && pin.id == selectedPinId && pin.coordinates == point
        }
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (error: ActivityNotFoundException) {
            Log.w(TAG, "Settings action unavailable: $action", error)
            showError(getString(R.string.settings_unavailable))
        }
    }

    private fun openMapAttribution(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (error: ActivityNotFoundException) {
            Log.w(TAG, "Cannot open map attribution", error)
            Toast.makeText(this, R.string.browser_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun savePin(point: Coordinates, existing: SavedPin? = null) {
        val name = EditText(this).apply {
            hint = getString(R.string.pin_name_hint)
            isSingleLine = true
            setText(existing?.name.orEmpty())
            selectAll()
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.pin_name else R.string.rename_pin)
            .setView(name)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = name.text.toString().trim()
                if (value.length !in 1..60) {
                    name.error = getString(R.string.invalid_name)
                } else {
                    if (existing == null) pins.add(value, point) else pins.rename(existing.id, value)
                    renderPins()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun renderPins() {
        val container = findViewById<LinearLayout>(R.id.pins)
        container.removeAllViews()
        val saved = pins.read()
        findViewById<TextView>(R.id.pin_count).text = resources.getQuantityString(R.plurals.pin_count, saved.size, saved.size)
        findViewById<View>(R.id.manage_pins).visibility = if (saved.isEmpty()) View.GONE else View.VISIBLE
        if (saved.isEmpty()) {
            container.addView(TextView(this).apply {
                setText(R.string.no_pins)
                setTextColor(getColor(R.color.muted))
                textSize = 12f
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(40)
            })
        }
        saved.forEach { pin ->
            container.addView(TextView(this).apply {
                tag = pin
                text = pin.name
                textSize = 14f
                maxWidth = dp(180)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                setTextColor(getColor(R.color.text))
                setBackgroundResource(R.drawable.pin_card)
                setPadding(dp(16), 0, dp(16), 0)
                contentDescription = getString(R.string.saved_pin_entry, pin.name, pin.coordinates.display())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)
                ).apply { marginEnd = dp(8) }
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                            AccessibilityNodeInfo.ACTION_LONG_CLICK, getString(R.string.pin_actions, pin.name)
                        ))
                    }
                }
                setOnClickListener {
                    loadPin(pin)
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                setOnLongClickListener {
                    pinActions(pin)
                    true
                }
            })
        }
        updatePreview(recenterPad = false)
    }

    private fun loadPin(pin: SavedPin) {
        setCoordinateInputs(pin.coordinates, pin.id)
        latitude.clearFocus()
        longitude.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(window.decorView.windowToken, 0)
        Toast.makeText(this, getString(R.string.pin_loaded, pin.name), Toast.LENGTH_SHORT).show()
    }

    private fun pinActions(pin: SavedPin) {
        AlertDialog.Builder(this)
            .setTitle(pin.name)
            .setItems(arrayOf(getString(R.string.load_pin), getString(R.string.rename_pin), getString(R.string.delete))) { _, action ->
                when (action) {
                    0 -> loadPin(pin)
                    1 -> savePin(pin.coordinates, pin)
                    else -> deletePin(pin)
                }
            }
            .show()
    }

    private fun managePins() {
        val saved = pins.read()
        if (saved.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.saved_pins)
            .setItems(saved.map { getString(R.string.saved_pin_entry, it.name, it.coordinates.display()) }
                .toTypedArray()) { _, index -> pinActions(saved[index]) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun deletePin(pin: SavedPin) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_pin, pin.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                pins.delete(pin.id)
                if (selectedPinId == pin.id) selectedPinId = null
                renderPins()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PERMISSIONS = 1
        private const val TAG = "MainActivity"
    }
}
