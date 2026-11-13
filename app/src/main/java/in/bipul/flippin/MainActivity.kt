package `in`.bipul.flippin

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var state: SharedPreferences
    private lateinit var pins: PinStore
    private lateinit var latitude: EditText
    private lateinit var longitude: EditText
    private var pending: Coordinates? = null

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
        latitude.setText(state.getString(MockLocationService.LATITUDE, ""))
        longitude.setText(state.getString(MockLocationService.LONGITUDE, ""))
        pending = savedInstanceState?.let {
            Coordinates.parse(it.getString("pending_lat").orEmpty(), it.getString("pending_lon").orEmpty())
        }
        findViewById<Button>(R.id.start).setOnClickListener { readCoordinates()?.let(::prepareStart) }
        findViewById<Button>(R.id.stop).setOnClickListener {
            stopService(Intent(this, MockLocationService::class.java))
        }
        findViewById<Button>(R.id.save_pin).setOnClickListener { readCoordinates()?.let(::savePin) }
        findViewById<Button>(R.id.developer_options).setOnClickListener {
            openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        }
        findViewById<Button>(R.id.location_settings).setOnClickListener {
            openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        }
        findViewById<TextView>(R.id.version).text = getString(R.string.version, BuildConfig.VERSION_NAME)
        renderPins()
    }

    override fun onStart() {
        super.onStart()
        state.registerOnSharedPreferenceChangeListener(this)
        renderState()
    }

    override fun onResume() {
        super.onResume()
        renderSetup()
    }

    override fun onStop() {
        state.unregisterOnSharedPreferenceChangeListener(this)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pending?.let {
            outState.putString("pending_lat", it.latitude.toString())
            outState.putString("pending_lon", it.longitude.toString())
        }
        super.onSaveInstanceState(outState)
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences, key: String?) = renderState()

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
            return
        }
        if (!locationEnabled()) {
            showError(getString(R.string.location_off))
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
            setTextColor(getColor(if (running) R.color.success else R.color.muted))
        }
        val point = Coordinates.parse(
            state.getString(MockLocationService.LATITUDE, "").orEmpty(),
            state.getString(MockLocationService.LONGITUDE, "").orEmpty()
        )
        findViewById<TextView>(R.id.status_detail).text =
            if (running && point != null) getString(R.string.active_coordinates, point.display())
            else getString(R.string.idle_detail)
        findViewById<Button>(R.id.stop).isEnabled = running
        findViewById<Button>(R.id.start).setText(if (running) R.string.apply else R.string.start)
        showError(state.getString(MockLocationService.ERROR, null))
    }

    private fun renderSetup() {
        findViewById<TextView>(R.id.setup_status).text = listOfNotNull(
            getString(if (mockAllowed()) R.string.mock_ready else R.string.mock_not_ready),
            if (!locationEnabled()) getString(R.string.location_off) else null
        ).joinToString("\n")
    }

    private fun showError(message: String?) {
        findViewById<TextView>(R.id.error).apply {
            text = message
            visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
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

    private fun savePin(point: Coordinates) {
        val name = EditText(this).apply {
            hint = getString(R.string.pin_name_hint)
            isSingleLine = true
            setPadding(32, 24, 32, 24)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.pin_name)
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
                    pins.add(value, point)
                    renderPins()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    @SuppressLint("SetTextI18n")
    private fun renderPins() {
        val container = findViewById<LinearLayout>(R.id.pins)
        container.removeAllViews()
        val saved = pins.read()
        if (saved.isEmpty()) {
            container.addView(TextView(this).apply {
                setText(R.string.no_pins)
                setTextColor(getColor(R.color.muted))
            })
        }
        saved.forEach { pin ->
            container.addView(Button(this).apply {
                text = getString(R.string.saved_pin_entry, pin.name, pin.coordinates.display())
                isAllCaps = false
                setOnClickListener {
                    // Keep editable coordinates locale-independent and lossless.
                    latitude.setText(pin.coordinates.latitude.toString())
                    longitude.setText(pin.coordinates.longitude.toString())
                    latitude.error = null
                    longitude.error = null
                }
                setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage(getString(R.string.delete_pin, pin.name))
                        .setPositiveButton(R.string.delete) { _, _ ->
                            pins.delete(pin.id)
                            renderPins()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    true
                }
            })
        }
    }

    companion object {
        private const val PERMISSIONS = 1
        private const val TAG = "MainActivity"
    }
}
