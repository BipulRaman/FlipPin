package `in`.bipul.flippin

import android.app.AppOpsManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.os.Process
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
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
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowGeocoder
import org.robolectric.shadow.api.Shadow
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mockConstruction
import java.util.Locale
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], shadows = [ShadowVectorMap::class, ShadowMapLibre::class])
class MainActivityTest {
    @Test
    fun mapReplacesLocalNameLabelsWithEnglishLatinFallbackAndKeepsRouteShields() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val pad = controller.get().findViewById<CoordinateMapView>(R.id.coordinate_pad)
            val updates = Shadow.extract<ShadowVectorMap>(pad.mapView).labelUpdates
            assertEquals(setOf("place"), updates.keys)
            val expression = updates.getValue("place").toString()
            assertTrue(expression.startsWith("[\"case\""))
            assertTrue(expression.contains("[\"coalesce\", [\"get\", \"name:en\"], \"\"]"))
            assertTrue(expression.indexOf("name:en") < expression.indexOf("name_en"))
            assertTrue(expression.indexOf("name_en") < expression.indexOf("name:latin"))
            assertFalse(expression.contains("[\"get\", \"name\"]"))
            assertFalse(expression.contains("name:nonlatin"))
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
                val button = activity.findViewById<Button>(R.id.search_location)
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
                assertEquals(13.0, activity.findViewById<CoordinateMapView>(R.id.coordinate_pad).map!!.cameraPosition.zoom, 0.0)
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
            activity.findViewById<Button>(R.id.search_location).performClick()
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
            activity.findViewById<EditText>(R.id.longitude).setText("179.99999")
            activity.findViewById<Button>(R.id.scale_fine).performClick()
            activity.findViewById<Button>(R.id.nudge_east).performClick()
            activity.findViewById<Button>(R.id.nudge_east).performClick()
            var point = Coordinates.parse(
                activity.findViewById<EditText>(R.id.latitude).text.toString(),
                activity.findViewById<EditText>(R.id.longitude).text.toString()
            )!!
            assertEquals(-179.99999, point.longitude, 1e-9)
            val pad = activity.findViewById<CoordinateMapView>(R.id.coordinate_pad)
            pad.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
            assertEquals(12.50001, activity.findViewById<EditText>(R.id.latitude).text.toString().toDouble(), 1e-9)
            controller.recreate()
            activity = controller.get()
            assertTrue(activity.findViewById<Button>(R.id.scale_fine).isSelected)
            assertEquals(PinScale.FINE, activity.findViewById<CoordinateMapView>(R.id.coordinate_pad).scale)
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
            activity.findViewById<Button>(R.id.scale_fine).performClick()
            val pad = activity.findViewById<CoordinateMapView>(R.id.coordinate_pad)
            val map = pad.map!!
            val renderer = Shadow.extract<ShadowVectorMap>(pad.mapView)
            pad.layout(0, 0, 360, 320)
            val center = map.cameraPosition.target!!
            val expected = LatLng(12.516, 45.027)
            val time = SystemClock.uptimeMillis()
            assertTrue(renderer.click!!.onMapClick(expected))
            val selected = Coordinates.parse(
                activity.findViewById<EditText>(R.id.latitude).text.toString(),
                activity.findViewById<EditText>(R.id.longitude).text.toString()
            )!!
            assertEquals(expected.latitude, selected.latitude, 0.000001)
            assertEquals(expected.longitude, selected.longitude, 0.000001)
            assertEquals(center.latitude, map.cameraPosition.target!!.latitude, 1e-9)
            assertEquals(center.longitude, map.cameraPosition.target!!.longitude, 1e-9)
            val marker = renderer.marker!!
            assertEquals(selected.latitude, marker.position.latitude, 1e-9)
            assertTrue(renderer.longClick!!.onMapLongClick(marker.position))
            val drag = MotionEvent.obtain(time, time + 1000, MotionEvent.ACTION_MOVE, 200f, 120f, 0)
            val release = MotionEvent.obtain(time, time + 1100, MotionEvent.ACTION_UP, 200f, 120f, 0)
            try {
                assertTrue(pad.dispatchTouchEvent(drag))
                assertTrue(pad.dispatchTouchEvent(release))
            } finally {
                drag.recycle()
                release.recycle()
            }
            val dragged = map.projection.fromScreenLocation(android.graphics.PointF(200f, 120f))
            assertEquals(dragged.latitude, activity.findViewById<EditText>(R.id.latitude).text.toString().toDouble(), 0.000001)
            assertEquals(center.latitude, map.cameraPosition.target!!.latitude, 1e-9)
            assertEquals(center.longitude, map.cameraPosition.target!!.longitude, 1e-9)
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
            Shadow.extract<ShadowVectorMap>(pad.mapView).failure!!.onDidFailLoadingMap("Network unavailable")
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
            assertNull(Shadow.extract<ShadowVectorMap>(pad.mapView).marker)
            pad.map!!.cameraPosition = CameraPosition.Builder().target(LatLng(48.8584, 2.2945)).zoom(15.0).build()
            val center = pad.map!!.cameraPosition.target!!
            controller.recreate()
            activity = controller.get()
            pad = activity.findViewById(R.id.coordinate_pad)
            assertEquals(15.0, pad.map!!.cameraPosition.zoom, 1e-9)
            assertEquals(center.latitude, pad.map!!.cameraPosition.target!!.latitude, 1e-9)
            assertEquals(center.longitude, pad.map!!.cameraPosition.target!!.longitude, 1e-9)
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
