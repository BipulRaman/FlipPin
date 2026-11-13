package `in`.bipul.flippin

import android.app.AppOpsManager
import android.provider.Settings
import android.location.LocationManager
import android.content.pm.PackageManager
import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.Uri
import android.webkit.WebResourceRequest
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.text.Spanned
import android.text.style.ClickableSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowGeocoder
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkInfo
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mock
import org.mockito.Mockito.doReturn
import java.time.Duration
import java.util.Locale
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class MainActivityTest {
    @Before
    fun connectInternet() = setInternet(true)

    private fun setInternet(validated: Boolean) {
        val connectivity = RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(connectivity)
        shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true
        ))
        val network = connectivity.activeNetwork!!
        val capabilities = NetworkCapabilities()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (validated) shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadow.setNetworkCapabilities(network, capabilities)
        shadow.networkCallbacks.forEach {
            it.onAvailable(network)
            it.onCapabilitiesChanged(network, capabilities)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun CoordinateMapView.event(type: String, data: JSONObject = JSONObject()) {
        val shadow = shadowOf(requireNotNull(webView))
        val generation = Uri.parse(shadow.lastLoadedUrl).getQueryParameter("generation")!!.toInt()
        val script = shadow.lastEvaluatedJavascript
        val revision = script?.let { JSONObject(it.substringAfter("setState(").dropLast(1)).getInt("revision") } ?: 0
        data.put("generation", generation).put("type", type).put("revision", revision)
        (shadow.getJavascriptInterface("FlipPin") as CoordinateMapView.MapBridge).postMessage(data.toString())
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun CoordinateMapView.camera() = Bundle().also(::saveCamera)

    private fun distanceMeters(from: Coordinates, to: Coordinates): Double {
        val latitude = Math.toRadians(to.latitude - from.latitude)
        val longitude = Math.toRadians(to.longitude - from.longitude)
        val a = sin(latitude / 2).pow(2) + cos(Math.toRadians(from.latitude)) *
            cos(Math.toRadians(to.latitude)) * sin(longitude / 2).pow(2)
        return 2 * 6_371_008.8 * asin(sqrt(a))
    }

    private fun CoordinateMapView.renderedState(): JSONObject {
        val script = shadowOf(requireNotNull(webView)).lastEvaluatedJavascript
        return JSONObject(script.substringAfter("setState(").dropLast(1))
    }

    private fun View.touch(action: Int, x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(time, time, action, x, y, 0)
        dispatchTouchEvent(event)
        event.recycle()
    }

    private fun MainActivity.preview() = Coordinates.parse(
        findViewById<EditText>(R.id.latitude).text.toString(),
        findViewById<EditText>(R.id.longitude).text.toString()
    )!!

    @Test
    fun networkLossAndHandoverIgnoreStaleConnectivitySnapshotsAndOldNetworkEvents() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val connectivity = activity.getSystemService(ConnectivityManager::class.java)
            val callback = shadowOf(connectivity).networkCallbacks.single()
            val previous = connectivity.activeNetwork!!
            val capabilities = connectivity.getNetworkCapabilities(previous)!!
            callback.onLost(previous)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.online_map).visibility)
            assertTrue(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            val replacement = ShadowNetwork.newInstance(123)
            callback.onAvailable(replacement)
            callback.onCapabilitiesChanged(replacement, capabilities)
            callback.onLost(previous)
            callback.onCapabilitiesChanged(previous, NetworkCapabilities())
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.online_map).visibility)
            callback.onLost(replacement)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.online_map).visibility)
            assertNull(activity.findViewById<CoordinateMapView>(R.id.coordinate_pad).webView)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun bottomStatusTracksSessionAndDraftChangesWithoutTopBanners() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val hint = activity.findViewById<TextView>(R.id.draft_hint)
            val stop = activity.findViewById<Button>(R.id.stop)
            val appBar = activity.findViewById<LinearLayout>(R.id.app_bar)
            assertEquals(listOf(activity.getString(R.string.app_name)),
                (0 until appBar.childCount).map { appBar.getChildAt(it) }
                    .filterIsInstance<TextView>().map { it.text.toString() })
            assertEquals(activity.getString(R.string.draft_idle), hint.text.toString())
            assertEquals(View.GONE, stop.visibility)
            val serviceController = Robolectric.buildService(MockLocationService::class.java).create()
            try {
                val service = serviceController.get()
                service.onStartCommand(Intent(service, MockLocationService::class.java).apply {
                    putExtra(MockLocationService.LATITUDE, "12.5")
                    putExtra(MockLocationService.LONGITUDE, "45.25")
                }, 0, 1)
                activity.findViewById<EditText>(R.id.latitude).setText("12.5")
                activity.findViewById<EditText>(R.id.longitude).setText("45.25")
                assertEquals(activity.getString(R.string.draft_live), hint.text.toString())
                assertEquals(View.VISIBLE, stop.visibility)
                assertTrue(hint.isShown)
                assertTrue(activity.findViewById<View>(R.id.action_bar).isShown)
                activity.findViewById<EditText>(R.id.latitude).setText("13.5")
                assertEquals(activity.getString(R.string.draft_active), hint.text.toString())
                assertTrue(activity.findViewById<Button>(R.id.start).isEnabled)
            } finally {
                serviceController.destroy()
            }
            assertEquals(activity.getString(R.string.draft_idle), hint.text.toString())
            assertEquals(View.GONE, stop.visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun offlineHidesOnlineContentAndRetainsDraftPinsAdjustmentsAndTheActiveSession() {
        val serviceController = Robolectric.buildService(MockLocationService::class.java).create()
        val service = serviceController.get()
        service.onStartCommand(Intent(service, MockLocationService::class.java).apply {
            putExtra(MockLocationService.LATITUDE, "12.5")
            putExtra(MockLocationService.LONGITUDE, "45.25")
        }, 0, 1)
        PinStore(service.getSharedPreferences("saved_pins", Context.MODE_PRIVATE))
            .add("Offline pin", Coordinates(20.0, 30.0))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            var activity = controller.get()
            val pad = activity.findViewById<CoordinateMapView>(R.id.coordinate_pad)
            pad.event("ready")
            activity.findViewById<EditText>(R.id.latitude).setText("48.123456789")
            val camera = pad.camera()
            setInternet(false)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.online_search).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.online_map).visibility)
            assertNull(pad.webView)
            assertEquals("48.123456789", activity.findViewById<EditText>(R.id.latitude).text.toString())
            assertTrue(activity.findViewById<Button>(R.id.start).isShown)
            assertEquals(activity.getString(R.string.apply), activity.findViewById<Button>(R.id.start).text.toString())
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.stop).visibility)
            activity.findViewById<LinearLayout>(R.id.pins).getChildAt(0).performClick()
            assertEquals("20.0", activity.findViewById<EditText>(R.id.latitude).text.toString())
            // The joystick still works offline; during a session it moves the live location, not the draft.
            activity.findViewById<View>(R.id.joystick).performAccessibilityAction(R.id.move_north, null)
            val walked = activity.findViewById<EditText>(R.id.latitude).text.toString()
            assertEquals(5.0, distanceMeters(Coordinates(12.5, 45.25), Coordinates(walked.toDouble(), 45.25)), 0.1)
            assertEquals("45.25", activity.findViewById<EditText>(R.id.longitude).text.toString())
            val step = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
            assertEquals(MockLocationService.ACTION_MOVE, step.action)
            assertEquals(walked.toDouble(), step.getStringExtra(MockLocationService.LATITUDE)!!.toDouble(), 1e-7)
            assertEquals(0f, step.getFloatExtra(MockLocationService.SPEED, -1f), 0f)
            assertTrue(MockLocationService.isRunning)
            controller.recreate()
            activity = controller.get()
            assertEquals(View.GONE, activity.findViewById<View>(R.id.online_map).visibility)
            assertNull(activity.findViewById<CoordinateMapView>(R.id.coordinate_pad).webView)
            assertEquals(walked, activity.findViewById<EditText>(R.id.latitude).text.toString())
            setInternet(true)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.online_search).visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.online_map).visibility)
            val restoredPad = activity.findViewById<CoordinateMapView>(R.id.coordinate_pad)
            restoredPad.event("ready")
            assertEquals(walked.toDouble(), restoredPad.renderedState().getJSONObject("point").getDouble("latitude"), 0.0)
            assertEquals(48.123456789, camera.getDouble("map_latitude"), 0.0)
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        } finally {
            controller.pause().stop().destroy()
            serviceController.destroy()
        }
    }

    @Test
    @Config(sdk = [35])
    fun lateSearchResultsDoNotReappearAfterGoingOfflineAndReconnectAllowsNewSearch() {
        ShadowGeocoder.setIsPresent(true)
        val callbacks = mutableListOf<Geocoder.GeocodeListener>()
        mockConstruction(Geocoder::class.java) { geocoder, _ ->
            doAnswer {
                callbacks.add(it.getArgument(2))
                null
            }.`when`(geocoder).getFromLocationName(anyString(), anyInt(), any(Geocoder.GeocodeListener::class.java))
        }.use {
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val activity = controller.get()
                val input = activity.findViewById<EditText>(R.id.location_query)
                val search = activity.findViewById<View>(R.id.search_location)
                input.setText("Paris")
                search.performClick()
                setInternet(false)
                val places = mutableListOf(place("Paris, France", 48.8566, 2.3522), place("Paris, Texas", 33.66, -95.55))
                callbacks.single().onGeocode(places)
                assertFalse(activity.suggestions.isShowing)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.search_status).visibility)
                setInternet(true)
                search.performClick()
                callbacks.last().onGeocode(places)
                assertTrue(activity.suggestions.isShowing)
                assertEquals(2, activity.suggestions.listView!!.adapter.count)
            } finally {
                controller.pause().stop().destroy()
            }
        }
    }

    @Test
    fun staleAndInvalidMapMessagesCannotOverwriteNewNativeEdits() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val pad = activity.findViewById<CoordinateMapView>(R.id.coordinate_pad)
            pad.event("ready")
            val staleRevision = pad.renderedState().getInt("revision")
            activity.findViewById<EditText>(R.id.latitude).setText("10.123456789")
            activity.findViewById<EditText>(R.id.longitude).setText("20.987654321")
            val shadow = shadowOf(requireNotNull(pad.webView))
            val bridge = shadow.getJavascriptInterface("FlipPin") as CoordinateMapView.MapBridge
            bridge.postMessage(JSONObject().put("generation", Uri.parse(shadow.lastLoadedUrl)
                .getQueryParameter("generation")!!.toInt()).put("revision", staleRevision)
                .put("type", "pick").put("latitude", 5).put("longitude", 6).toString())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("10.123456789", activity.findViewById<EditText>(R.id.latitude).text.toString())
            pad.event("pick", JSONObject().put("latitude", 91).put("longitude", 6))
            assertEquals("10.123456789", activity.findViewById<EditText>(R.id.latitude).text.toString())
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.map_retry).visibility)
            val camera = pad.camera()
            pad.event("camera", JSONObject().put("latitude", 20).put("longitude", 30).put("zoom", -1))
            assertEquals(camera.getDouble("map_latitude"), pad.camera().getDouble("map_latitude"), 0.0)
            pad.retryTiles()
            bridge.postMessage(JSONObject().put("type", "ready").put("generation", -1).toString())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("20.987654321", activity.findViewById<EditText>(R.id.longitude).text.toString())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun mapCanReadOnlyBundledAssetsAndTheHttpsTileProvider() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val pad = controller.get().findViewById<CoordinateMapView>(R.id.coordinate_pad)
            val browser = requireNotNull(pad.webView)
            val client = shadowOf(browser).webViewClient
            fun request(url: String) = mock(WebResourceRequest::class.java).also {
                doReturn(Uri.parse(url)).`when`(it).url
            }
            val document = client.shouldInterceptRequest(browser,
                request("https://appassets.androidplatform.net/map/index.html"))!!
            document.data.bufferedReader().use { assertTrue(it.readText().contains("map.js")) }
            assertTrue(document.responseHeaders.getValue("Content-Security-Policy").contains("script-src 'self'"))
            assertNull(client.shouldInterceptRequest(browser,
                request("https://tiles.openfreemap.org/styles/liberty")))
            for (url in listOf(
                "file:///data/local/private.txt", "content://private/data",
                "http://tiles.openfreemap.org/styles/liberty", "https://example.com/script.js",
                "https://appassets.androidplatform.net/map/../private.txt"
            )) {
                assertEquals(403, client.shouldInterceptRequest(browser, request(url))!!.statusCode)
                assertTrue(client.shouldOverrideUrlLoading(browser, request(url)))
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    @Config(sdk = [35])
    fun searchesPlacesAndSelectsAPreviewWithoutMovingTheActiveSession() {
        ShadowGeocoder.setIsPresent(true)
        val serviceController = Robolectric.buildService(MockLocationService::class.java).create()
        val service = serviceController.get()
        service.onStartCommand(Intent(service, MockLocationService::class.java).apply {
            putExtra(MockLocationService.LATITUDE, "12.5")
            putExtra(MockLocationService.LONGITUDE, "45.25")
        }, 0, 1)
        val eiffel = place("Eiffel Tower, Paris", 48.858412345, 2.294512345)
        val louvre = place("Louvre Museum, Paris", 48.860611, 2.337644)
        val requested = mutableListOf<String>()
        mockConstruction(Geocoder::class.java) { geocoder, construction ->
            assertEquals(Locale.ENGLISH, construction.arguments()[1])
            doAnswer {
                val listener = it.getArgument<Geocoder.GeocodeListener>(2)
                val query = it.getArgument<String>(0)
                requested += query
                when (query) {
                    "Nowhere" -> listener.onGeocode(mutableListOf())
                    "Offline" -> listener.onError("Network unavailable")
                    "Louvre" -> listener.onGeocode(mutableListOf(louvre))
                    else -> listener.onGeocode(mutableListOf(eiffel, louvre))
                }
                null
            }.`when`(geocoder).getFromLocationName(anyString(), anyInt(), any(Geocoder.GeocodeListener::class.java))
        }.use {
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val activity = controller.get()
                val input = activity.findViewById<EditText>(R.id.location_query)
                val button = activity.findViewById<View>(R.id.search_location)
                val status = activity.findViewById<TextView>(R.id.search_status)
                val clear = activity.findViewById<View>(R.id.clear_query)
                assertEquals(View.GONE, clear.visibility)
                button.performClick()
                assertNotNull(input.error)
                for (typed in listOf("P", "Pa", "Par", "Pari")) input.setText(typed)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
                assertEquals(listOf("Pari"), requested)
                assertTrue(activity.suggestions.isShowing)
                assertEquals(2, activity.suggestions.listView!!.adapter.count)
                assertEquals("12.5", activity.findViewById<EditText>(R.id.latitude).text.toString())
                activity.suggestions.performItemClick(0)
                assertFalse(activity.suggestions.isShowing)
                assertEquals("Eiffel Tower, Paris", input.text.toString())
                assertEquals("48.858412345", activity.findViewById<EditText>(R.id.latitude).text.toString())
                assertEquals("2.294512345", activity.findViewById<EditText>(R.id.longitude).text.toString())
                assertEquals(13.0, activity.findViewById<CoordinateMapView>(R.id.coordinate_pad).camera().getDouble("map_zoom"), 0.0)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
                assertEquals(1, requested.size)
                assertEquals("12.5", activity.getSharedPreferences(MockLocationService.STATE, Context.MODE_PRIVATE)
                    .getString(MockLocationService.LATITUDE, null))
                assertTrue(MockLocationService.isRunning)
                assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
                assertEquals(View.VISIBLE, clear.visibility)
                assertEquals(View.VISIBLE, status.visibility)
                clear.performClick()
                assertEquals("", input.text.toString())
                assertEquals(View.GONE, clear.visibility)
                assertEquals(View.GONE, status.visibility)
                assertTrue(input.hasFocus())
                assertEquals("48.858412345", activity.findViewById<EditText>(R.id.latitude).text.toString())
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
                assertEquals(1, requested.size)
                input.setText("Louvre")
                button.performClick()
                assertFalse(activity.suggestions.isShowing)
                assertEquals("48.860611", activity.findViewById<EditText>(R.id.latitude).text.toString())
                input.setText("Nowhere")
                button.performClick()
                assertEquals(activity.getString(R.string.search_empty), status.text.toString())
                input.setText("Offline")
                button.performClick()
                assertEquals(activity.getString(R.string.search_failed), status.text.toString())
                assertFalse(activity.suggestions.isShowing)
                assertEquals("48.860611", activity.findViewById<EditText>(R.id.latitude).text.toString())
                input.setText("Paris")
                input.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
                assertTrue(activity.suggestions.isShowing)
                val searches = requested.size
                controller.recreate()
                assertEquals("Paris", controller.get().findViewById<EditText>(R.id.location_query).text.toString())
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
                assertFalse(controller.get().suggestions.isShowing)
                assertEquals(searches, requested.size)
                val restored = controller.get()
                val restoredClear = restored.findViewById<View>(R.id.clear_query)
                assertEquals(View.VISIBLE, restoredClear.visibility)
                restored.findViewById<EditText>(R.id.location_query)
                    .onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
                assertTrue(restored.suggestions.isShowing)
                restoredClear.performClick()
                assertFalse(restored.suggestions.isShowing)
                assertEquals("", restored.findViewById<EditText>(R.id.location_query).text.toString())
            } finally {
                controller.pause().stop().destroy()
                serviceController.destroy()
            }
        }
    }

    @Test
    fun missingGeocodingServiceExplainsTheAlternativeAndAttributionHasNoBackground() {
        ShadowGeocoder.setIsPresent(false)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<EditText>(R.id.location_query).setText("Paris")
            activity.findViewById<View>(R.id.search_location).performClick()
            assertEquals(activity.getString(R.string.search_unavailable),
                activity.findViewById<TextView>(R.id.search_status).text.toString())
            val map = activity.findViewById<View>(R.id.coordinate_pad)
            val attribution = activity.findViewById<TextView>(R.id.map_attribution)
            assertEquals(map.parent, attribution.parent)
            assertNull(attribution.background)
            val credit = attribution.text as Spanned
            credit.getSpans(0, credit.length, ClickableSpan::class.java).first().onClick(attribution)
            assertEquals("https://www.openstreetmap.org/copyright",
                shadowOf(activity).nextStartedActivity.data.toString())
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun validatesCoordinatesAndExplainsMissingMockAppSelection() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            shadowOf(activity.getSystemService(AppOpsManager::class.java)).setMode(
                AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), activity.packageName, AppOpsManager.MODE_IGNORED
            )
            val latitude = activity.findViewById<EditText>(R.id.latitude)
            val longitude = activity.findViewById<EditText>(R.id.longitude)
            activity.findViewById<Button>(R.id.start).performClick()
            assertNotNull(latitude.error)
            latitude.setText("0")
            longitude.setText("181")
            activity.findViewById<Button>(R.id.start).performClick()
            assertNotNull(longitude.error)
            longitude.setText("0")
            activity.findViewById<Button>(R.id.start).performClick()
            assertEquals(
                activity.getString(R.string.mock_not_ready),
                activity.findViewById<TextView>(R.id.error).text.toString()
            )
            assertFalse(MockLocationService.isRunning)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.setup_details).visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun restoresSavedPinsAndEditedCoordinatesWithoutRevivingAStaleSession() {
        val application = RuntimeEnvironment.getApplication()
        PinStore(application.getSharedPreferences("saved_pins", Context.MODE_PRIVATE))
            .add("Test office", Coordinates(-12.5, 45.25))
        application.getSharedPreferences(MockLocationService.STATE, Context.MODE_PRIVATE)
            .edit().putBoolean(MockLocationService.RUNNING, true).apply()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            var activity = controller.get()
            assertFalse(activity.findViewById<Button>(R.id.stop).isEnabled)
            val pins = activity.findViewById<LinearLayout>(R.id.pins)
            assertEquals(1, pins.childCount)
            pins.getChildAt(0).performClick()
            assertTrue(pins.getChildAt(0).isSelected)
            assertEquals("-12.5", activity.findViewById<EditText>(R.id.latitude).text.toString())
            controller.recreate()
            activity = controller.get()
            assertTrue(activity.findViewById<LinearLayout>(R.id.pins).getChildAt(0).isSelected)
            activity.findViewById<EditText>(R.id.longitude).setText("-180")
            controller.recreate()
            activity = controller.get()
            assertEquals("-180", activity.findViewById<EditText>(R.id.longitude).text.toString())
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pins).visibility)
            assertFalse(activity.findViewById<Button>(R.id.stop).isEnabled)
            assertFalse(activity.findViewById<LinearLayout>(R.id.pins).getChildAt(0).isSelected)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun joystickSteersAtTheSelectedSpeedAndArrowKeysStepFiveMetres() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            var activity = controller.get()
            activity.findViewById<EditText>(R.id.latitude).setText("12.5")
            activity.findViewById<EditText>(R.id.longitude).setText("179.99995")
            assertEquals(MovementSpeed.WALK.ordinal,
                activity.findViewById<Spinner>(R.id.movement_speed).selectedItemPosition)
            activity.selectSpeed(MovementSpeed.DRIVE)
            val joystick = activity.findViewById<JoystickView>(R.id.joystick)
            val centerX = joystick.width / 2f
            val centerY = joystick.height / 2f
            assertEquals(activity.getString(R.string.move_hint_preview),
                activity.findViewById<TextView>(R.id.move_detail).text.toString())
            // Pushing past the edge still uses the selected driving speed.
            joystick.touch(MotionEvent.ACTION_DOWN, centerX, centerY)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            assertEquals(Coordinates(12.5, 179.99995), activity.preview())
            val pushedAt = SystemClock.elapsedRealtime()
            joystick.touch(MotionEvent.ACTION_MOVE, joystick.width + 50f, centerY)
            assertEquals("50 km/h", activity.findViewById<TextView>(R.id.move_speed).text.toString())
            assertEquals("E 90° · driving speed", activity.findViewById<TextView>(R.id.move_detail).text.toString())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            val held = (SystemClock.elapsedRealtime() - pushedAt) / 1000.0
            joystick.touch(MotionEvent.ACTION_UP, joystick.width + 50f, centerY)
            assertEquals(16.0, activity.findViewById<CoordinateMapView>(R.id.coordinate_pad).camera().getDouble("map_zoom"), 0.0)
            var point = activity.preview()
            assertTrue(point.longitude < -179.9999)
            assertEquals(12.5, point.latitude, 1e-7)
            // Robolectric may advance one 16 ms UI frame while updating the map.
            assertEquals(held * 50 / 3.6, distanceMeters(Coordinates(12.5, 179.99995), point), 0.3)
            assertEquals(activity.getString(R.string.move_stopped),
                activity.findViewById<TextView>(R.id.move_speed).text.toString())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(point, activity.preview())
            activity.selectSpeed(MovementSpeed.WALK)
            // A light push uses the same fixed walking speed as a full push.
            joystick.touch(MotionEvent.ACTION_DOWN, centerX, centerY)
            joystick.touch(MotionEvent.ACTION_MOVE, centerX - 9f * joystick.resources.displayMetrics.density,
                centerY + 9f * joystick.resources.displayMetrics.density)
            assertTrue(activity.findViewById<TextView>(R.id.move_detail).text.startsWith("SW 225° · walking pace"))
            assertEquals("5.0 km/h", activity.findViewById<TextView>(R.id.move_speed).text.toString())
            val walkingAt = SystemClock.elapsedRealtime()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            val walkingSeconds = (SystemClock.elapsedRealtime() - walkingAt) / 1000.0
            joystick.touch(MotionEvent.ACTION_UP, centerX, centerY)
            val walked = activity.preview()
            assertTrue(walked.latitude < point.latitude && walked.longitude < point.longitude)
            assertEquals(walkingSeconds * 5 / 3.6, distanceMeters(point, walked), 0.2)
            val pad = activity.findViewById<CoordinateMapView>(R.id.coordinate_pad)
            pad.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
            val north = activity.preview()
            assertEquals(5.0, distanceMeters(walked, north), 0.1)
            assertEquals(walked.longitude, north.longitude, 0.0)
            joystick.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
            val west = activity.preview()
            assertEquals(5.0, distanceMeters(north, west), 0.1)
            assertEquals(north.latitude, west.latitude, 1e-7)
            // Holding a key steps once; auto-repeat would move faster than any real pace.
            joystick.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 1))
            assertEquals(west, activity.preview())
            activity.selectSpeed(MovementSpeed.CYCLE)
            controller.recreate()
            activity = controller.get()
            assertEquals(MovementSpeed.CYCLE.ordinal,
                activity.findViewById<Spinner>(R.id.movement_speed).selectedItemPosition)
            point = activity.preview()
            assertEquals(west, point)
            assertEquals(point.display(), activity.findViewById<TextView>(R.id.preview).text.toString())
            activity.findViewById<ImageButton>(R.id.copy_coordinates).performClick()
            assertEquals(
                "${point.latitude}, ${point.longitude}",
                activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
            )
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
            activity.findViewById<EditText>(R.id.longitude).setText("181")
            assertFalse(activity.findViewById<ImageButton>(R.id.copy_coordinates).isEnabled)
            assertEquals(activity.getString(R.string.pad_invalid), activity.findViewById<TextView>(R.id.preview).text.toString())
            val restored = activity.findViewById<JoystickView>(R.id.joystick)
            restored.touch(MotionEvent.ACTION_DOWN, centerX, centerY)
            restored.touch(MotionEvent.ACTION_MOVE, centerX, -100f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            restored.touch(MotionEvent.ACTION_UP, centerX, -100f)
            assertNotNull(activity.findViewById<EditText>(R.id.longitude).error)
            assertEquals("181", activity.findViewById<EditText>(R.id.longitude).text.toString())
            assertEquals(activity.getString(R.string.move_stopped),
                activity.findViewById<TextView>(R.id.move_speed).text.toString())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun joystickMovesTheLiveSessionWithSpeedAndHeadingAndStopsOnReleaseOrPause() {
        val serviceController = Robolectric.buildService(MockLocationService::class.java).create()
        val service = serviceController.get()
        service.onStartCommand(Intent(service, MockLocationService::class.java).apply {
            putExtra(MockLocationService.LATITUDE, "10.0")
            putExtra(MockLocationService.LONGITUDE, "20.0")
        }, 0, 1)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val started = shadowOf(RuntimeEnvironment.getApplication())
            // A typed draft is not teleported to: movement starts from the live location.
            activity.findViewById<EditText>(R.id.latitude).setText("48.0")
            activity.findViewById<EditText>(R.id.longitude).setText("2.0")
            assertEquals(activity.getString(R.string.move_hint_live),
                activity.findViewById<TextView>(R.id.move_detail).text.toString())
            val joystick = activity.findViewById<JoystickView>(R.id.joystick)
            val centerX = joystick.width / 2f
            joystick.touch(MotionEvent.ACTION_DOWN, centerX, joystick.height / 2f)
            val pushedAt = SystemClock.elapsedRealtime()
            joystick.touch(MotionEvent.ACTION_MOVE, centerX, -1000f)
            val first = started.nextStartedService
            assertEquals(MockLocationService.ACTION_MOVE, first.action)
            assertEquals("10.0", first.getStringExtra(MockLocationService.LATITUDE))
            assertEquals("20.0", first.getStringExtra(MockLocationService.LONGITUDE))
            assertEquals(5 / 3.6f, first.getFloatExtra(MockLocationService.SPEED, 0f), 0.01f)
            assertEquals(0f, first.getFloatExtra(MockLocationService.BEARING, -1f), 0f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200))
            // Updates follow about every half second while the knob is held.
            val updates = generateSequence { started.nextStartedService }.toList()
            assertEquals(2, updates.size)
            assertTrue(updates.all { abs(it.getFloatExtra(MockLocationService.SPEED, 0f) - 5 / 3.6f) < 0.01f })
            val held = (SystemClock.elapsedRealtime() - pushedAt) / 1000.0
            joystick.touch(MotionEvent.ACTION_UP, centerX, -1000f)
            val stop = started.nextStartedService
            assertEquals(0f, stop.getFloatExtra(MockLocationService.SPEED, -1f), 0f)
            val stopped = Coordinates.parse(
                stop.getStringExtra(MockLocationService.LATITUDE)!!, stop.getStringExtra(MockLocationService.LONGITUDE)!!
            )!!
            // The haversine check uses a sphere, about 0.5% longer than WGS 84 metres north-south here.
            assertEquals(held * 5 / 3.6, distanceMeters(Coordinates(10.0, 20.0), stopped), 0.1)
            assertEquals(20.0, stopped.longitude, 0.0)
            assertEquals(stopped.rounded(), activity.preview())
            assertNull(started.nextStartedService)
            // The joystick already updated the session, so the bar says so and Apply is not needed.
            val apply = activity.findViewById<Button>(R.id.start)
            assertEquals(activity.getString(R.string.draft_live),
                activity.findViewById<TextView>(R.id.draft_hint).text.toString())
            assertFalse(apply.isEnabled)
            activity.findViewById<EditText>(R.id.latitude).setText("10.5")
            assertEquals(activity.getString(R.string.draft_active),
                activity.findViewById<TextView>(R.id.draft_hint).text.toString())
            assertTrue(apply.isEnabled)
            // The next drag continues from where the session stopped; leaving the screen stops it.
            joystick.touch(MotionEvent.ACTION_DOWN, centerX, joystick.height / 2f)
            joystick.touch(MotionEvent.ACTION_MOVE, joystick.width + 50f, joystick.height / 2f)
            val resumed = started.nextStartedService
            assertEquals(stopped.latitude.toString(), resumed.getStringExtra(MockLocationService.LATITUDE))
            assertEquals(90f, resumed.getFloatExtra(MockLocationService.BEARING, -1f), 0f)
            controller.pause()
            assertEquals(0f, started.nextStartedService.getFloatExtra(MockLocationService.SPEED, -1f), 0f)
            controller.resume()
            assertNull(started.nextStartedService)
            // Another screen (for example, one opened from the notification) moves the session meanwhile;
            // returning here must continue from there instead of jumping back.
            service.onStartCommand(Intent(service, MockLocationService::class.java)
                .setAction(MockLocationService.ACTION_MOVE)
                .putExtra(MockLocationService.LATITUDE, "10.5")
                .putExtra(MockLocationService.LONGITUDE, "20.5"), 0, 2)
            controller.pause().stop().restart().resume()
            joystick.touch(MotionEvent.ACTION_DOWN, centerX, joystick.height / 2f)
            joystick.touch(MotionEvent.ACTION_MOVE, centerX, -1000f)
            val elsewhere = started.nextStartedService
            assertEquals("10.5", elsewhere.getStringExtra(MockLocationService.LATITUDE))
            assertEquals("20.5", elsewhere.getStringExtra(MockLocationService.LONGITUDE))
            joystick.touch(MotionEvent.ACTION_UP, centerX, -1000f)
        } finally {
            controller.pause().stop().destroy()
            serviceController.destroy()
        }
    }

    @Test
    fun joystickTurnsAndSpeedChangesReachTheSessionWithinATenthOfASecond() {
        val serviceController = Robolectric.buildService(MockLocationService::class.java).create()
        val service = serviceController.get()
        service.onStartCommand(Intent(service, MockLocationService::class.java).apply {
            putExtra(MockLocationService.LATITUDE, "10.0")
            putExtra(MockLocationService.LONGITUDE, "20.0")
        }, 0, 1)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val started = shadowOf(RuntimeEnvironment.getApplication())
            val activity = controller.get()
            activity.selectSpeed(MovementSpeed.DRIVE)
            val joystick = activity.findViewById<JoystickView>(R.id.joystick)
            val centerX = joystick.width / 2f
            val centerY = joystick.height / 2f
            joystick.touch(MotionEvent.ACTION_DOWN, centerX, centerY)
            joystick.touch(MotionEvent.ACTION_MOVE, centerX, -1000f)
            assertEquals(0f, started.nextStartedService.getFloatExtra(MockLocationService.BEARING, -1f), 0f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            assertNull(started.nextStartedService)
            joystick.touch(MotionEvent.ACTION_MOVE, joystick.width + 50f, centerY)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
            val turn = started.nextStartedService
            assertEquals(90f, turn.getFloatExtra(MockLocationService.BEARING, -1f), 0f)
            assertEquals(50 / 3.6f, turn.getFloatExtra(MockLocationService.SPEED, 0f), 0.01f)
            joystick.touch(MotionEvent.ACTION_MOVE, centerX + 13 * joystick.resources.displayMetrics.density, centerY)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(160))
            assertNull(started.nextStartedService)
            assertEquals("50 km/h", activity.findViewById<TextView>(R.id.move_speed).text.toString())
            activity.selectSpeed(MovementSpeed.RUN)
            val slower = started.nextStartedService
            assertEquals(10 / 3.6f, slower.getFloatExtra(MockLocationService.SPEED, 99f), 0.01f)
            assertEquals(90f, slower.getFloatExtra(MockLocationService.BEARING, -1f), 0f)
            assertEquals("10 km/h", activity.findViewById<TextView>(R.id.move_speed).text.toString())
            joystick.touch(MotionEvent.ACTION_UP, centerX, centerY)
            assertEquals(0f, started.nextStartedService.getFloatExtra(MockLocationService.SPEED, -1f), 0f)
        } finally {
            controller.pause().stop().destroy()
            serviceController.destroy()
        }

    }

    private fun MainActivity.selectSpeed(speed: MovementSpeed) {
        findViewById<Spinner>(R.id.movement_speed).setSelection(speed.ordinal)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
    }

    @Test
    fun tappingTheRealMapKeepsTheCameraStableWithoutApplyingToTheActiveSession() {
        val serviceController = Robolectric.buildService(MockLocationService::class.java).create()
        val service = serviceController.get()
        service.onStartCommand(Intent(service, MockLocationService::class.java).apply {
            putExtra(MockLocationService.LATITUDE, "12.5")
            putExtra(MockLocationService.LONGITUDE, "179.99999")
        }, 0, 1)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertTrue(MockLocationService.isRunning)
            val pad = activity.findViewById<CoordinateMapView>(R.id.coordinate_pad)
            pad.event("ready")
            val center = pad.camera()
            val expected = Coordinates(12.516, 45.027)
            pad.event("pick", JSONObject().put("latitude", expected.latitude).put("longitude", expected.longitude))
            val selected = Coordinates.parse(
                activity.findViewById<EditText>(R.id.latitude).text.toString(),
                activity.findViewById<EditText>(R.id.longitude).text.toString()
            )!!
            assertEquals(expected.latitude, selected.latitude, 0.000001)
            assertEquals(expected.longitude, selected.longitude, 0.000001)
            assertEquals(center.getDouble("map_latitude"), pad.camera().getDouble("map_latitude"), 1e-9)
            assertEquals(center.getDouble("map_longitude"), pad.camera().getDouble("map_longitude"), 1e-9)
            pad.event("pick", JSONObject().put("latitude", 12.5161234567).put("longitude", 405.0271234567))
            assertEquals("12.516123", activity.findViewById<EditText>(R.id.latitude).text.toString())
            assertEquals("45.027123", activity.findViewById<EditText>(R.id.longitude).text.toString())
            assertEquals(center.getDouble("map_latitude"), pad.camera().getDouble("map_latitude"), 1e-9)
            assertEquals(center.getDouble("map_longitude"), pad.camera().getDouble("map_longitude"), 1e-9)
            val state = activity.getSharedPreferences(MockLocationService.STATE, Context.MODE_PRIVATE)
            assertEquals("179.99999", state.getString(MockLocationService.LONGITUDE, null))
            assertTrue(MockLocationService.isRunning)
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
            assertEquals(activity.getString(R.string.apply), activity.findViewById<Button>(R.id.start).text.toString())
        } finally {
            controller.pause().stop().destroy()
            serviceController.destroy()
        }
    }

    @Test
    fun tileFailureShowsRetryWithoutDiscardingTheSelectedCoordinates() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<EditText>(R.id.latitude).setText("48.8584")
            activity.findViewById<EditText>(R.id.longitude).setText("2.2945")
            val pad = activity.findViewById<CoordinateMapView>(R.id.coordinate_pad)
            pad.event("error", JSONObject().put("message", "Network unavailable"))
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.map_retry).visibility)
            activity.findViewById<Button>(R.id.map_retry).performClick()
            assertEquals(View.GONE, activity.findViewById<View>(R.id.map_retry).visibility)
            assertEquals("48.8584", activity.findViewById<EditText>(R.id.latitude).text.toString())
            assertEquals("2.2945", activity.findViewById<EditText>(R.id.longitude).text.toString())
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun restoresPannedCameraAndPreservesCoordinatesOutsideMapCoverage() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            var activity = controller.get()
            activity.findViewById<EditText>(R.id.latitude).setText("90")
            activity.findViewById<EditText>(R.id.longitude).setText("12.123456789")
            var pad = activity.findViewById<CoordinateMapView>(R.id.coordinate_pad)
            assertEquals("90", activity.findViewById<EditText>(R.id.latitude).text.toString())
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.map_limit).visibility)
            pad.event("ready")
            assertTrue(pad.renderedState().isNull("point"))
            pad.event("camera", JSONObject().put("latitude", 48.8584).put("longitude", 2.2945).put("zoom", 15.0))
            val center = pad.camera()
            controller.recreate()
            activity = controller.get()
            pad = activity.findViewById(R.id.coordinate_pad)
            assertEquals(15.0, pad.camera().getDouble("map_zoom"), 1e-9)
            assertEquals(center.getDouble("map_latitude"), pad.camera().getDouble("map_latitude"), 1e-9)
            assertEquals(center.getDouble("map_longitude"), pad.camera().getDouble("map_longitude"), 1e-9)
            assertEquals("90", activity.findViewById<EditText>(R.id.latitude).text.toString())
            assertEquals("12.123456789", activity.findViewById<EditText>(R.id.longitude).text.toString())
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.map_limit).visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun remembersSetupExpansionAcrossRecreation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            var activity = controller.get()
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.setup_details).visibility)
            activity.findViewById<Button>(R.id.setup_toggle).performClick()
            assertEquals(View.GONE, activity.findViewById<View>(R.id.setup_details).visibility)
            controller.recreate()
            activity = controller.get()
            assertEquals(View.GONE, activity.findViewById<View>(R.id.setup_details).visibility)
            activity.findViewById<Button>(R.id.setup_toggle).performClick()
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.setup_details).visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun setupStepThreeAsksForLocationPermissionInPlaceAndCompletesSetup() {
        val application = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            shadowOf(activity.getSystemService(AppOpsManager::class.java)).setMode(
                AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), activity.packageName, AppOpsManager.MODE_ALLOWED
            )
            shadowOf(activity.getSystemService(LocationManager::class.java)).apply {
                setLocationEnabled(true)
                setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            }
            controller.pause().resume()
            val check = activity.findViewById<TextView>(R.id.check_permission)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.setup_details).visibility)
            assertEquals(activity.getString(R.string.check_permission_needed), check.text.toString())
            activity.findViewById<Button>(R.id.allow_permission).performClick()
            val request = shadowOf(activity).lastRequestedPermission
            assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in request.requestedPermissions)
            shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                IntArray(request.requestedPermissions.size) { PackageManager.PERMISSION_GRANTED })
            assertEquals(activity.getString(R.string.check_permission_ready), check.text.toString())
            assertEquals(View.GONE, activity.findViewById<View>(R.id.allow_permission).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.setup_card).visibility)
            assertNull(shadowOf(application).nextStartedService)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun blockedLocationPermissionOffersAppSettings() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val allow = activity.findViewById<Button>(R.id.allow_permission)
            allow.performClick()
            val request = shadowOf(activity).lastRequestedPermission
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                IntArray(request.requestedPermissions.size) { PackageManager.PERMISSION_DENIED })
            assertEquals(activity.getString(R.string.app_settings), allow.text.toString())
            assertEquals(activity.getString(R.string.location_permission_blocked),
                activity.findViewById<TextView>(R.id.error).text.toString())
            allow.performClick()
            val settings = shadowOf(activity).nextStartedActivity
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.action)
            assertEquals("package:${activity.packageName}", settings.data.toString())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun place(name: String, latitude: Double, longitude: Double) = Address(Locale.ENGLISH).apply {
        this.latitude = latitude
        this.longitude = longitude
        setAddressLine(0, name)
    }
}
