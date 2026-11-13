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
import android.os.SystemClock
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
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var state: SharedPreferences
    private lateinit var pins: PinStore
    private lateinit var latitude: EditText
    private lateinit var longitude: EditText
    private lateinit var pad: CoordinateMapView
    private lateinit var locationSearch: LocationSearch
    private lateinit var query: EditText
    internal lateinit var suggestions: ListPopupWindow
    private lateinit var suggestionAdapter: ArrayAdapter<PlaceResult>
    private val suggestTask = Runnable { lookUpPlaces(explicit = false) }
    private var updatingQuery = false
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
    private var setupReady: Boolean? = null
    private var permissionBlocked = false
    private lateinit var joystick: JoystickView
    private var movementSpeed = MovementSpeed.WALK
    private var moveBearing = 0.0
    private var moveSpeed = 0.0
    // Exact position while the joystick is held; the fields show it rounded.
    private var movePosition: Coordinates? = null
    private var moveBlocked = false
    private var lastMoveAt = 0L
    private var lastSentAt = 0L
    private var sentBearing = 0.0
    private var sentSpeed = 0.0
    // Last location this screen gave the running session.
    private var liveSent: Coordinates? = null
    private val moveTick = object : Runnable {
        override fun run() {
            advanceMovement()
            if (moveSpeed > 0.0) joystick.postDelayed(this, MOVE_TICK_MS)
        }
    }

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
        query = findViewById(R.id.location_query)
        suggestionAdapter = object : ArrayAdapter<PlaceResult>(this, R.layout.item_suggestion) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply { text = getItem(position)?.name }
        }
        suggestions = ListPopupWindow(this).apply {
            anchorView = findViewById(R.id.search_box)
            setAdapter(suggestionAdapter)
            setBackgroundDrawable(getDrawable(R.drawable.card))
            isModal = false
            inputMethodMode = ListPopupWindow.INPUT_METHOD_NEEDED
            verticalOffset = dp(4)
            setOnItemClickListener { _, _, position, _ -> suggestionAdapter.getItem(position)?.let(::selectPlace) }
        }
        findViewById<View>(R.id.search_location).setOnClickListener { searchLocation() }
        val clearQuery = findViewById<View>(R.id.clear_query)
        clearQuery.setOnClickListener {
            query.text.clear()
            query.requestFocus()
            query.post {
                if (query.hasFocus()) getSystemService(InputMethodManager::class.java).showSoftInput(query, 0)
            }
        }
        query.setOnFocusChangeListener { _, focused ->
            if (!focused) suggestions.dismiss()
            renderActionBar()
        }
        query.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                searchLocation()
                true
            } else false
        }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable?) {
                clearQuery.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
                onQueryEdited()
            }
        })
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
        setupExpanded = savedInstanceState?.takeIf { it.containsKey("setup_expanded") }
            ?.getBoolean("setup_expanded") ?: !(mockAllowed() && locationEnabled() && hasLocationPermission())
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
        findViewById<Button>(R.id.allow_permission).setOnClickListener {
            if (permissionBlocked) {
                openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            } else {
                requestPermissions(missingPermissions(), SETUP_PERMISSIONS)
            }
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
        joystick = findViewById(R.id.joystick)
        joystick.onMove = ::onJoystick
        joystick.onStep = ::stepJoystick
        movementSpeed = MovementSpeed.entries.firstOrNull {
            it.name == state.getString("movement_speed", null)
        } ?: MovementSpeed.WALK
        findViewById<Spinner>(R.id.movement_speed).apply {
            adapter = ArrayAdapter(this@MainActivity, R.layout.item_speed,
                MovementSpeed.entries.map { getString(it.label) })
            setSelection(movementSpeed.ordinal)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selected = MovementSpeed.entries[position]
                    if (selected == movementSpeed) return
                    advanceMovement()
                    movementSpeed = selected
                    if (moveSpeed > 0.0) {
                        moveSpeed = selected.kilometersPerHour / 3.6
                        if (sessionRunning()) movePosition?.let { sendMovement(it, moveSpeed) }
                    }
                    renderMovement()
                    state.edit().putString("movement_speed", selected.name).apply()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
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
        // Never leave the location moving once the screen is no longer in use.
        joystick.release()
        stopMovement()
        pad.pauseMap()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Quick-settings toggles do not pause the activity, so re-check setup when focus returns.
        if (hasFocus) renderSetup()
    }

    override fun onDestroy() {
        joystick.removeCallbacks(moveTick)
        query.removeCallbacks(suggestTask)
        suggestions.dismiss()
        locationSearch.close()
        pad.destroyMap()
        super.onDestroy()
    }

    override fun onStop() {
        // Another screen (for example, one opened from the notification) may move the session meanwhile.
        liveSent = null
        monitoringNetwork = false
        connectivity.unregisterNetworkCallback(networkCallback)
        state.unregisterOnSharedPreferenceChangeListener(this)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
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
        // Restoring the search text must not send another lookup.
        updatingQuery = true
        try {
            super.onRestoreInstanceState(savedInstanceState)
        } finally {
            updatingQuery = false
        }
        selectedPinId = savedInstanceState.getString("selected_pin")
        updatePreview(recenterPad = false)
        pad.restoreCamera(savedInstanceState)
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences, key: String?) = renderState()

    private fun onQueryEdited() {
        if (updatingQuery) return
        query.removeCallbacks(suggestTask)
        searchGeneration++
        query.error = null
        findViewById<View>(R.id.search_status).visibility = View.GONE
        if (online && query.text.trim().length >= MIN_SUGGESTION_LENGTH) {
            query.postDelayed(suggestTask, SUGGESTION_DELAY_MS)
        } else {
            suggestions.dismiss()
        }
    }

    private fun searchLocation() {
        query.removeCallbacks(suggestTask)
        lookUpPlaces(explicit = true)
    }

    private fun lookUpPlaces(explicit: Boolean) {
        if (!online) return
        val text = query.text.toString().trim()
        query.error = null
        if (text.isEmpty()) {
            query.error = getString(R.string.search_required)
            query.requestFocus()
            return
        }
        if (!Geocoder.isPresent()) {
            suggestions.dismiss()
            showSearchStatus(getString(R.string.search_unavailable), failed = true)
            return
        }
        val request = ++searchGeneration
        if (explicit) {
            showSearchStatus(getString(R.string.search_loading))
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(query.windowToken, 0)
        }
        locationSearch.search(text) { result ->
            runOnUiThread {
                if (isDestroyed || isFinishing || !online || request != searchGeneration) return@runOnUiThread
                result.fold(
                    onSuccess = { places -> showSuggestions(places, explicit) },
                    onFailure = { error ->
                        Log.w(TAG, "Location search failed", error)
                        suggestions.dismiss()
                        showSearchStatus(getString(R.string.search_failed), failed = true)
                    }
                )
            }
        }
    }

    private fun showSuggestions(places: List<PlaceResult>, explicit: Boolean) {
        findViewById<View>(R.id.search_status).visibility = View.GONE
        suggestionAdapter.clear()
        suggestionAdapter.addAll(places)
        when {
            places.isEmpty() -> {
                suggestions.dismiss()
                if (explicit) showSearchStatus(getString(R.string.search_empty))
            }
            explicit && places.size == 1 -> selectPlace(places.single())
            findViewById<View>(R.id.search_box).isAttachedToWindow -> suggestions.show()
        }
    }

    private fun selectPlace(place: PlaceResult) {
        suggestions.dismiss()
        query.removeCallbacks(suggestTask)
        searchGeneration++
        updatingQuery = true
        try {
            query.setText(place.name)
            query.setSelection(query.length())
        } finally {
            updatingQuery = false
        }
        query.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(query.windowToken, 0)
        setCoordinateInputs(place.coordinates)
        pad.zoomTo(CoordinateMapView.AREA_ZOOM)
        showSearchStatus(getString(R.string.search_selected, place.name))
        findViewById<ScrollView>(R.id.content_scroll).smoothScrollTo(0, findViewById<View>(R.id.online_map).top)
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
            query.removeCallbacks(suggestTask)
            suggestions.dismiss()
            if (query.hasFocus()) {
                getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(query.windowToken, 0)
            }
            query.clearFocus()
            findViewById<View>(R.id.search_status).visibility = View.GONE
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
        val permissions = missingPermissions()
        if (permissions.isNotEmpty()) {
            pending = point
            requestPermissions(permissions, PERMISSIONS)
        } else {
            start(point)
        }
    }

    private fun missingPermissions(): Array<String> = buildList {
        if (!hasLocationPermission()) {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSIONS && requestCode != SETUP_PERMISSIONS) return
        val point = if (requestCode == PERMISSIONS) pending else null
        if (requestCode == PERMISSIONS) pending = null
        // An empty result means the request was interrupted rather than answered.
        if (grantResults.isNotEmpty()) {
            if (hasLocationPermission()) {
                point?.let(::start)
            } else {
                // Without a rationale after a denial, Android will not show the prompt again.
                permissionBlocked = !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
                showError(getString(
                    if (permissionBlocked) R.string.location_permission_blocked else R.string.location_permission_required
                ))
                setSetupExpanded(true)
            }
        }
        renderSetup()
    }

    private fun start(point: Coordinates) {
        try {
            startForegroundService(Intent(this, MockLocationService::class.java).apply {
                putExtra(MockLocationService.LATITUDE, point.latitude.toString())
                putExtra(MockLocationService.LONGITUDE, point.longitude.toString())
            })
            liveSent = point
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
        val running = sessionRunning()
        if (!running) liveSent = null
        findViewById<Button>(R.id.stop).apply {
            isEnabled = running
            visibility = if (running) View.VISIBLE else View.GONE
        }
        findViewById<Button>(R.id.start).setText(if (running) R.string.apply else R.string.start)
        renderDraft()
        showError(state.getString(MockLocationService.ERROR, null))
        renderMovement()
    }

    private fun sessionRunning(): Boolean =
        MockLocationService.isRunning && state.getBoolean(MockLocationService.RUNNING, false)

    private fun liveLocation(): Coordinates? = liveSent ?: MockLocationService.liveAnchor

    /** During a session the joystick updates the live location itself, so Apply is only for a different preview. */
    private fun renderDraft() {
        val running = sessionRunning()
        val preview = Coordinates.parse(latitude.text.toString(), longitude.text.toString())
        val live = if (running) liveLocation() else null
        val isLive = running && (moveSpeed > 0.0 || preview != null && preview.rounded() == live?.rounded())
        findViewById<TextView>(R.id.draft_hint).setText(when {
            !running -> R.string.draft_idle
            isLive -> R.string.draft_live
            else -> R.string.draft_active
        })
        findViewById<Button>(R.id.start).apply {
            isEnabled = !isLive
            alpha = if (isEnabled) 1f else 0.5f
        }
    }

    private fun renderSetup() {
        val mock = mockAllowed()
        val location = locationEnabled()
        val permission = hasLocationPermission()
        if (permission) permissionBlocked = false
        renderCheck(R.id.check_mock, R.id.developer_options, mock, R.string.check_mock_ready, R.string.check_mock_needed)
        renderCheck(R.id.check_location, R.id.location_settings, location,
            R.string.check_location_ready, R.string.check_location_needed)
        renderCheck(R.id.check_permission, R.id.allow_permission, permission,
            R.string.check_permission_ready, R.string.check_permission_needed)
        findViewById<View>(R.id.mock_hint).visibility = if (mock) View.GONE else View.VISIBLE
        findViewById<View>(R.id.permission_hint).visibility = if (permission) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.allow_permission)
            .setText(if (permissionBlocked) R.string.app_settings else R.string.allow_permission)
        val ready = listOf(mock, location, permission).count { it }
        findViewById<TextView>(R.id.setup_status).text = getString(R.string.setup_progress, ready)
        val complete = ready == 3
        val previous = setupReady
        setupReady = complete
        when {
            previous == true && !complete -> setSetupExpanded(true)
            previous == false && complete && setupExpanded -> {
                setSetupExpanded(false)
                Toast.makeText(this, R.string.setup_complete, Toast.LENGTH_SHORT).show()
            }
            else -> renderSetupCard()
        }
    }

    private fun renderCheck(textId: Int, actionId: Int, ready: Boolean, readyText: Int, neededText: Int) {
        findViewById<TextView>(textId).apply {
            setText(if (ready) readyText else neededText)
            setTextColor(getColor(if (ready) R.color.success else R.color.text))
        }
        findViewById<View>(actionId).visibility = if (ready) View.GONE else View.VISIBLE
    }

    private fun renderSetupCard() {
        findViewById<View>(R.id.setup_card).visibility =
            if (setupReady == true && !setupExpanded) View.GONE else View.VISIBLE
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

    private fun onJoystick(bearing: Double, moving: Boolean) {
        if (!moving) {
            stopMovement()
            // Releasing lets the next drag retry after an invalid starting point.
            moveBlocked = false
            return
        }
        advanceMovement()
        moveBearing = bearing
        val starting = moveSpeed == 0.0
        if (starting) {
            if (moveBlocked) return
            movePosition = movementOrigin() ?: run {
                moveBlocked = true
                return
            }
            lastMoveAt = SystemClock.elapsedRealtime()
            pad.zoomForMovement()
            joystick.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            joystick.postDelayed(moveTick, MOVE_TICK_MS)
        }
        moveSpeed = movementSpeed.kilometersPerHour / 3.6
        if (starting && sessionRunning()) movePosition?.let { sendMovement(it, moveSpeed) }
        renderMovement()
    }

    private fun stepJoystick(bearing: Double) {
        if (moveSpeed > 0.0) return
        val next = (movementOrigin() ?: return).travelled(bearing, STEP_METERS)
        moveBearing = bearing
        setCoordinateInputs(next.rounded())
        pad.zoomForMovement()
        if (sessionRunning()) sendMovement(next, 0.0)
        joystick.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** During a session the joystick moves the live location, otherwise the preview pin. */
    private fun movementOrigin(): Coordinates? = if (sessionRunning()) liveLocation() else readCoordinates()

    private fun advanceMovement() {
        val position = movePosition ?: return
        val now = SystemClock.elapsedRealtime()
        val seconds = (now - lastMoveAt).coerceIn(0L, MAX_MOVE_GAP_MS) / 1000.0
        lastMoveAt = now
        if (seconds == 0.0 || moveSpeed == 0.0) return
        val next = position.travelled(moveBearing, moveSpeed * seconds)
        movePosition = next
        setCoordinateInputs(next.rounded())
        val sinceSent = now - lastSentAt
        // Turns and speed changes go out quickly so the session never projects far along a stale heading.
        val due = sinceSent >= SEND_INTERVAL_MS || sinceSent >= CHANGE_SEND_MS && velocityChanged()
        if (sessionRunning() && due) sendMovement(next, moveSpeed)
    }

    private fun velocityChanged(): Boolean {
        val turn = abs((moveBearing - sentBearing + 540.0) % 360.0 - 180.0)
        return turn > 3.0 || abs(moveSpeed - sentSpeed) > max(0.2, sentSpeed * 0.05)
    }

    private fun stopMovement() {
        joystick.removeCallbacks(moveTick)
        if (moveSpeed > 0.0) {
            advanceMovement()
            moveSpeed = 0.0
            if (sessionRunning()) movePosition?.let { sendMovement(it, 0.0) }
            renderMovement()
            renderDraft()
        }
        movePosition = null
    }

    private fun sendMovement(point: Coordinates, metersPerSecond: Double) {
        try {
            startService(Intent(this, MockLocationService::class.java)
                .setAction(MockLocationService.ACTION_MOVE)
                .putExtra(MockLocationService.LATITUDE, point.latitude.toString())
                .putExtra(MockLocationService.LONGITUDE, point.longitude.toString())
                .putExtra(MockLocationService.SPEED, metersPerSecond.toFloat())
                .putExtra(MockLocationService.BEARING, moveBearing.toFloat()))
            liveSent = point
            lastSentAt = SystemClock.elapsedRealtime()
            sentBearing = moveBearing
            sentSpeed = metersPerSecond
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Cannot update the live location", error)
        } catch (error: SecurityException) {
            Log.w(TAG, "Cannot update the live location", error)
        }
    }

    private fun renderMovement() {
        val speed = findViewById<TextView>(R.id.move_speed)
        val detail = findViewById<TextView>(R.id.move_detail)
        if (moveSpeed == 0.0) {
            speed.setText(R.string.move_stopped)
            detail.setText(if (sessionRunning()) R.string.move_hint_live else R.string.move_hint_preview)
            return
        }
        val kilometersPerHour = moveSpeed * 3.6
        speed.text = getString(R.string.move_speed, if (kilometersPerHour < 10) {
            String.format(Locale.ROOT, "%.1f", kilometersPerHour)
        } else {
            kilometersPerHour.roundToInt().toString()
        })
        detail.text = getString(
            R.string.move_heading,
            COMPASS[(moveBearing / 45).roundToInt() % COMPASS.size],
            moveBearing.roundToInt() % 360,
            getString(when {
                kilometersPerHour < 7 -> R.string.pace_walking
                kilometersPerHour < 14 -> R.string.pace_running
                kilometersPerHour < 28 -> R.string.pace_cycling
                else -> R.string.pace_driving
            })
        )
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
        renderDraft()
        val container = findViewById<LinearLayout>(R.id.pins)
        for (index in 0 until container.childCount) {
            val row = container.getChildAt(index)
            val pin = row.tag as? SavedPin
            row.isSelected = pin != null && pin.id == selectedPinId && pin.coordinates == point
        }
    }

    private fun openSettings(action: String) = openSettings(Intent(action))

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            Log.w(TAG, "Settings action unavailable: ${intent.action}", error)
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
        private const val SETUP_PERMISSIONS = 2
        private const val MIN_SUGGESTION_LENGTH = 3
        private const val SUGGESTION_DELAY_MS = 350L
        private const val MOVE_TICK_MS = 50L
        // Caps the jump after a stalled frame.
        private const val MAX_MOVE_GAP_MS = 250L
        private const val SEND_INTERVAL_MS = 500L
        private const val CHANGE_SEND_MS = 100L
        private val COMPASS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        private const val TAG = "MainActivity"
    }
}
