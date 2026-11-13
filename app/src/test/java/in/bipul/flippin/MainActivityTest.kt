package `in`.bipul.flippin

import android.app.AppOpsManager
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
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
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
import java.util.Locale
import org.json.JSONObject
import kotlin.math.asin
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
            activity.findViewById<Button>(R.id.pace_walk).performClick()
            activity.findViewById<Button>(R.id.nudge_north).performClick()
            val walked = activity.findViewById<EditText>(R.id.latitude).text.toString()
            assertEquals(5.0, distanceMeters(Coordinates(20.0, 30.0), Coordinates(walked.toDouble(), 30.0)), 0.1)
            assertEquals("30.0", activity.findViewById<EditText>(R.id.longitude).text.toString())
            assertEquals("12.5", service.getSharedPreferences(MockLocationService.STATE, Context.MODE_PRIVATE)
                .getString(MockLocationService.LATITUDE, null))
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
                assertFalse(search.isEnabled)
                setInternet(false)
                assertTrue(search.isEnabled)
                val place = Address(Locale.ENGLISH).apply {
                    latitude = 48.8584
                    longitude = 2.2945
                    setAddressLine(0, "Paris")
                }
                callbacks.first().onGeocode(mutableListOf(place))
                assertEquals(0, activity.findViewById<LinearLayout>(R.id.search_results).childCount)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.search_status).visibility)
                setInternet(true)
                search.performClick()
                callbacks.last().onGeocode(mutableListOf(place))
                assertEquals(1, activity.findViewById<LinearLayout>(R.id.search_results).childCount)
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
        val matches = mutableListOf(Address(Locale.ENGLISH).apply {
            latitude = 48.858412345
            longitude = 2.294512345
            setAddressLine(0, "Eiffel Tower, Paris")
        })
        mockConstruction(Geocoder::class.java) { geocoder, construction ->
            assertEquals(Locale.ENGLISH, construction.arguments()[1])
            doAnswer {
                val listener = it.getArgument<Geocoder.GeocodeListener>(2)
                when (it.getArgument<String>(0)) {
                    "Nowhere" -> listener.onGeocode(mutableListOf())
                    "Offline" -> listener.onError("Network unavailable")
                    else -> listener.onGeocode(matches)
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
                val results = activity.findViewById<LinearLayout>(R.id.search_results)
                button.performClick()
                assertNotNull(input.error)
                input.setText("Paris")
                button.performClick()
                assertEquals(1, results.childCount)
                assertEquals("12.5", activity.findViewById<EditText>(R.id.latitude).text.toString())
                results.getChildAt(0).performClick()
                assertEquals("48.858412345", activity.findViewById<EditText>(R.id.latitude).text.toString())
                assertEquals("2.294512345", activity.findViewById<EditText>(R.id.longitude).text.toString())
                assertEquals(13.0, activity.findViewById<CoordinateMapView>(R.id.coordinate_pad).camera().getDouble("map_zoom"), 0.0)
                assertEquals(0, results.childCount)
                assertEquals("12.5", activity.getSharedPreferences(MockLocationService.STATE, Context.MODE_PRIVATE)
                    .getString(MockLocationService.LATITUDE, null))
                assertTrue(MockLocationService.isRunning)
                assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
                input.setText("Nowhere")
                button.performClick()
                assertEquals(activity.getString(R.string.search_empty), status.text.toString())
                input.setText("Offline")
                button.performClick()
                assertEquals(activity.getString(R.string.search_failed), status.text.toString())
                assertEquals(0, results.childCount)
                assertTrue(button.isEnabled)
                assertTrue(input.isEnabled)
                assertEquals("48.858412345", activity.findViewById<EditText>(R.id.latitude).text.toString())
                input.setText("Paris")
                input.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
                assertEquals(1, results.childCount)
                controller.recreate()
                assertEquals("Paris", controller.get().findViewById<EditText>(R.id.location_query).text.toString())
                assertEquals(0, controller.get().findViewById<LinearLayout>(R.id.search_results).childCount)
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
    fun synchronizesPreviewNudgesAndKeyboardControlsAndRestoresScale() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            var activity = controller.get()
            activity.findViewById<EditText>(R.id.latitude).setText("12.5")
            activity.findViewById<EditText>(R.id.longitude).setText("179.9998")
            activity.findViewById<Button>(R.id.pace_drive).performClick()
            assertEquals(15.0, activity.findViewById<CoordinateMapView>(R.id.coordinate_pad).camera().getDouble("map_zoom"), 0.0)
            activity.findViewById<Button>(R.id.nudge_east).performClick()
            activity.findViewById<Button>(R.id.nudge_east).performClick()
            var point = Coordinates.parse(
                activity.findViewById<EditText>(R.id.latitude).text.toString(),
                activity.findViewById<EditText>(R.id.longitude).text.toString()
            )!!
            assertTrue(point.longitude < -179.99)
            assertEquals(100.0, distanceMeters(Coordinates(12.5, 179.9998), point), 1.0)
            val pad = activity.findViewById<CoordinateMapView>(R.id.coordinate_pad)
            pad.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
            val north = Coordinates.parse(
                activity.findViewById<EditText>(R.id.latitude).text.toString(),
                activity.findViewById<EditText>(R.id.longitude).text.toString()
            )!!
            assertEquals(50.0, distanceMeters(point, north), 0.5)
            assertEquals(point.longitude, north.longitude, 0.0)
            controller.recreate()
            activity = controller.get()
            assertTrue(activity.findViewById<Button>(R.id.pace_drive).isSelected)
            assertEquals(Pace.DRIVE, activity.findViewById<CoordinateMapView>(R.id.coordinate_pad).pace)
            point = Coordinates.parse(
                activity.findViewById<EditText>(R.id.latitude).text.toString(),
                activity.findViewById<EditText>(R.id.longitude).text.toString()
            )!!
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
            activity.findViewById<Button>(R.id.nudge_north).performClick()
            assertNotNull(activity.findViewById<EditText>(R.id.longitude).error)
            assertEquals("181", activity.findViewById<EditText>(R.id.longitude).text.toString())
        } finally {
            controller.pause().stop().destroy()
        }
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
            activity.findViewById<Button>(R.id.pace_walk).performClick()
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
            assertEquals(View.GONE, activity.findViewById<View>(R.id.setup_details).visibility)
            activity.findViewById<Button>(R.id.setup_toggle).performClick()
            controller.recreate()
            activity = controller.get()
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.setup_details).visibility)
            activity.findViewById<Button>(R.id.setup_toggle).performClick()
            assertEquals(View.GONE, activity.findViewById<View>(R.id.setup_details).visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
