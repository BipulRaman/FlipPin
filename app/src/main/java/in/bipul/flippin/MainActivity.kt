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

class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var state: SharedPreferences
    private lateinit var pins: PinStore
    private lateinit var latitude: EditText
    private lateinit var longitude: EditText
    private lateinit var pad: CoordinateMapView
    private lateinit var locationSearch: LocationSearch
    private var searching = false
    private var pending: Coordinates? = null
    private var updatingFields = false
    private var selectedPinId: String? = null
    private var setupExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val screen = findViewById<View>(R.id.screen)
        screen.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }
        state = getSharedPreferences(MockLocationService.STATE, MODE_PRIVATE)
        pins = PinStore(getSharedPreferences("saved_pins", MODE_PRIVATE))
        latitude = findViewById(R.id.latitude)
        longitude = findViewById(R.id.longitude)
        pad = findViewById(R.id.coordinate_pad)
        locationSearch = LocationSearch(Geocoder(this, Locale.ENGLISH))
        findViewById<Button>(R.id.search_location).setOnClickListener { searchLocation() }
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
        findViewById<Button>(R.id.save_pin).setOnClickListener { readCoordinates()?.let { savePin(it) } }
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
        val initialScale = PinScale.entries.firstOrNull { it.name == savedInstanceState?.getString("pad_scale") }
            ?: PinScale.WORLD
        for ((id, scale) in scaleButtons()) {
            findViewById<Button>(id).setOnClickListener { selectScale(scale) }
        }
        selectScale(initialScale)
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

    override fun onLowMemory() {
        super.onLowMemory()
        pad.lowMemory()
    }

    override fun onStop() {
        state.unregisterOnSharedPreferenceChangeListener(this)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pad_scale", pad.scale.name)
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
        if (searching) return
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
        input.isEnabled = false
        findViewById<Button>(R.id.search_location).isEnabled = false
        showSearchStatus(getString(R.string.search_loading))
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
        locationSearch.search(query) { result ->
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    searching = false
                    input.isEnabled = true
                    findViewById<Button>(R.id.search_location).isEnabled = true
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
                setTextColor(getColor(R.color.text))
                setBackgroundResource(R.drawable.pin_card)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                minimumHeight = dp(48)
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
                setOnClickListener {
                    setCoordinateInputs(place.coordinates)
                    selectScale(PinScale.AREA)
                    results.removeAllViews()
                    showSearchStatus(getString(R.string.search_selected, place.name))
                    this@MainActivity.findViewById<EditText>(R.id.location_query).clearFocus()
                    pad.requestRectangleOnScreen(Rect(0, 0, pad.width, pad.height), false)
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
            return
        }
        if (!locationEnabled()) {
            showError(getString(R.string.location_off))
            setSetupExpanded(true)
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
            setTextColor(getColor(if (running) R.color.hero_accent else R.color.hero_muted))
        }
        val point = Coordinates.parse(
            state.getString(MockLocationService.LATITUDE, "").orEmpty(),
            state.getString(MockLocationService.LONGITUDE, "").orEmpty()
        )
        findViewById<TextView>(R.id.status_detail).text =
            if (running && point != null) getString(R.string.active_coordinates, point.display())
            else getString(R.string.idle_detail)
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
        findViewById<TextView>(R.id.setup_status).text =
            getString(R.string.setup_progress, checks.count { it.second })
    }

    private fun setSetupExpanded(expanded: Boolean) {
        setupExpanded = expanded
        findViewById<View>(R.id.setup_details).visibility = if (expanded) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.setup_toggle).apply {
            setText(if (expanded) R.string.setup_collapse else R.string.setup_expand)
            isSelected = expanded
        }
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

    private fun scaleButtons(): List<Pair<Int, PinScale>> = listOf(
        R.id.scale_world to PinScale.WORLD, R.id.scale_area to PinScale.AREA, R.id.scale_fine to PinScale.FINE
    )

    private fun selectScale(scale: PinScale) {
        pad.setScale(scale)
        scaleButtons().forEach { (id, value) -> findViewById<Button>(id).isSelected = value == scale }
        val step = String.format(Locale.ROOT, "%.5f", scale.step).trimEnd('0').trimEnd('.')
        findViewById<TextView>(R.id.nudge_step).text = getString(R.string.nudge_step, step)
    }

    private fun nudge(north: Int, east: Int) {
        readCoordinates()?.let { point ->
            setCoordinateInputs(point.nudged(north * pad.scale.step, east * pad.scale.step))
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
        findViewById<TextView>(R.id.preview).text = point?.display() ?: getString(
            if (latitude.text.isEmpty() && longitude.text.isEmpty()) R.string.pad_empty else R.string.pad_invalid
        )
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
        if (saved.isEmpty()) {
            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(20), dp(18), dp(20))
                setBackgroundResource(R.drawable.pin_card)
                addView(TextView(this@MainActivity).apply {
                    setText(R.string.pins_empty_title)
                    setTextColor(getColor(R.color.text))
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(this@MainActivity).apply {
                    setText(R.string.no_pins)
                    setTextColor(getColor(R.color.muted))
                    textSize = 13f
                    setPadding(0, dp(6), 0, 0)
                })
            })
        }
        saved.forEach { pin ->
            container.addView(LinearLayout(this).apply {
                tag = pin
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                contentDescription = getString(R.string.saved_pin_entry, pin.name, pin.coordinates.display())
                setPadding(dp(12), dp(12), dp(8), dp(12))
                setBackgroundResource(R.drawable.pin_card)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_pin)
                    imageTintList = ColorStateList.valueOf(getColor(R.color.accent))
                    setBackgroundResource(R.drawable.soft_button)
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = pin.name
                        textSize = 15f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(getColor(R.color.text))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = pin.coordinates.display()
                        textSize = 11f
                        typeface = Typeface.MONOSPACE
                        setTextColor(getColor(R.color.muted))
                        setPadding(0, dp(4), 0, 0)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(ImageButton(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_more)
                    setBackgroundResource(R.drawable.segment)
                    contentDescription = getString(R.string.pin_actions, pin.name)
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(pin.name)
                            .setItems(arrayOf(getString(R.string.rename_pin), getString(R.string.delete))) { _, action ->
                                if (action == 0) savePin(pin.coordinates, pin) else deletePin(pin)
                            }
                            .show()
                    }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                setOnClickListener {
                    setCoordinateInputs(pin.coordinates, pin.id)
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    latitude.clearFocus()
                    longitude.clearFocus()
                    getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(window.decorView.windowToken, 0)
                    this@MainActivity.findViewById<ScrollView>(R.id.screen)
                        .smoothScrollTo(0, this@MainActivity.findViewById<View>(R.id.pad_card).top)
                    Toast.makeText(this@MainActivity, getString(R.string.pin_loaded, pin.name), Toast.LENGTH_SHORT).show()
                }
                setOnLongClickListener {
                    deletePin(pin)
                    true
                }
            })
        }
        updatePreview(recenterPad = false)
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
