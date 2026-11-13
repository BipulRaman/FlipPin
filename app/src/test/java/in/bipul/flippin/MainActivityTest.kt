package `in`.bipul.flippin

import android.app.AppOpsManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
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
import android.widget.TextView
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
import org.osmdroid.util.GeoPoint
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class MainActivityTest {
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
            val map = pad.map
            map.setUseDataConnection(false)
            pad.layout(0, 0, 360, 320)
            map.layout(0, 0, 360, 320)
            val center = map.mapCenter
            val expected = map.projection.fromPixels(270, 160)
            val time = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(time, time, MotionEvent.ACTION_UP, 270f, 160f, 0)
            try {
                assertTrue(map.overlays.filterIsInstance<MapEventsOverlay>().single().onSingleTapConfirmed(event, map))
            } finally {
                event.recycle()
            }
            val selected = Coordinates.parse(
                activity.findViewById<EditText>(R.id.latitude).text.toString(),
                activity.findViewById<EditText>(R.id.longitude).text.toString()
            )!!
            assertEquals(expected.latitude, selected.latitude, 0.000001)
            assertEquals(expected.longitude, selected.longitude, 0.000001)
            assertEquals(center.latitude, map.mapCenter.latitude, 1e-9)
            assertEquals(center.longitude, map.mapCenter.longitude, 1e-9)
            val marker = map.overlays.filterIsInstance<Marker>().single()
            assertEquals(selected.latitude, marker.position.latitude, 1e-9)
            val bitmap = Bitmap.createBitmap(360, 320, Bitmap.Config.ARGB_8888)
            marker.draw(Canvas(bitmap), map.projection)
            bitmap.recycle()
            val pixel = map.projection.toPixels(marker.position, null)
            val hold = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, pixel.x.toFloat(), pixel.y - 20f, 0)
            val drag = MotionEvent.obtain(time, time + 1000, MotionEvent.ACTION_MOVE, 200f, 120f, 0)
            val release = MotionEvent.obtain(time, time + 1100, MotionEvent.ACTION_UP, 200f, 120f, 0)
            try {
                assertTrue(marker.onLongPress(hold, map))
                assertTrue(marker.onTouchEvent(drag, map))
                assertTrue(marker.onTouchEvent(release, map))
            } finally {
                hold.recycle()
                drag.recycle()
                release.recycle()
            }
            val dragged = map.projection.fromPixels(200, 120)
            assertEquals(dragged.latitude, activity.findViewById<EditText>(R.id.latitude).text.toString().toDouble(), 0.000001)
            assertEquals(center.latitude, map.mapCenter.latitude, 1e-9)
            assertEquals(center.longitude, map.mapCenter.longitude, 1e-9)
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
            pad.map.tileProvider.tileRequestCompleteHandlers.filterNotNull().forEach {
                it.sendEmptyMessage(MapTileProviderBase.MAPTILE_FAIL_ID)
            }
            shadowOf(Looper.getMainLooper()).idle()
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
            assertFalse(pad.map.overlays.filterIsInstance<Marker>().single().isEnabled)
            pad.map.controller.setZoom(15.0)
            pad.map.controller.setCenter(GeoPoint(48.8584, 2.2945))
            val center = pad.map.mapCenter
            controller.recreate()
            activity = controller.get()
            pad = activity.findViewById(R.id.coordinate_pad)
            assertEquals(15.0, pad.map.zoomLevelDouble, 1e-9)
            assertEquals(center.latitude, pad.map.mapCenter.latitude, 1e-9)
            assertEquals(center.longitude, pad.map.mapCenter.longitude, 1e-9)
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
