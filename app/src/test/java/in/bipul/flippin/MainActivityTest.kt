package `in`.bipul.flippin

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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
            assertEquals("-12.5", activity.findViewById<EditText>(R.id.latitude).text.toString())
            activity.findViewById<EditText>(R.id.longitude).setText("-180")
            controller.recreate()
            activity = controller.get()
            assertEquals("-180", activity.findViewById<EditText>(R.id.longitude).text.toString())
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pins).visibility)
            assertFalse(activity.findViewById<Button>(R.id.stop).isEnabled)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
