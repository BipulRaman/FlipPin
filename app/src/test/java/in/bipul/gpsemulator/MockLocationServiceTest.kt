package `in`.bipul.gpsemulator

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockingDetails
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MockLocationServiceTest {
    @Suppress("MissingPermission")
    @Test
    fun startsUpdatesAndStopsWithoutAutomaticallyRestarting() {
        val controller = Robolectric.buildService(MockLocationService::class.java).create()
        val service = controller.get()
        val preferences = service.getSharedPreferences(MockLocationService.STATE, Context.MODE_PRIVATE)
        try {
            val intent = Intent(service, MockLocationService::class.java).apply {
                putExtra(MockLocationService.LATITUDE, "12.5")
                putExtra(MockLocationService.LONGITUDE, "-45.25")
            }
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 1))
            assertTrue(MockLocationService.isRunning)
            assertTrue(preferences.getBoolean(MockLocationService.RUNNING, false))
            assertNull(preferences.getString(MockLocationService.ERROR, null))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertTrue(MockLocationService.isRunning)
            intent.putExtra(MockLocationService.LATITUDE, "-90")
            intent.putExtra(MockLocationService.LONGITUDE, "180")
            service.onStartCommand(intent, 0, 2)
            assertEquals("-90.0", preferences.getString(MockLocationService.LATITUDE, null))
            assertEquals("180.0", preferences.getString(MockLocationService.LONGITUDE, null))
        } finally {
            controller.destroy()
        }
        assertFalse(MockLocationService.isRunning)
        assertFalse(preferences.getBoolean(MockLocationService.RUNNING, true))
    }

    @Test
    fun joystickUpdatesMoveTheSessionWithSpeedAndBearingAndHaltWhenTheyStop() {
        mockConstruction(MockLocationEngine::class.java).use { engines ->
            val controller = Robolectric.buildService(MockLocationService::class.java).create()
            val service = controller.get()
            val preferences = service.getSharedPreferences(MockLocationService.STATE, Context.MODE_PRIVATE)
            fun move(latitude: String, longitude: String, speed: Float, bearing: Float, startId: Int) =
                service.onStartCommand(Intent(service, MockLocationService::class.java)
                    .setAction(MockLocationService.ACTION_MOVE)
                    .putExtra(MockLocationService.LATITUDE, latitude)
                    .putExtra(MockLocationService.LONGITUDE, longitude)
                    .putExtra(MockLocationService.SPEED, speed)
                    .putExtra(MockLocationService.BEARING, bearing), 0, startId)
            // The latest published fix: coordinates, speed (m/s), and bearing.
            fun fix() = mockingDetails(engines.constructed().single()).invocations
                .last { it.method.name == "publish" }.arguments
            try {
                move("10.0", "20.0", 5f, 90f, 1)
                assertFalse(MockLocationService.isRunning)
                assertNull(preferences.getString(MockLocationService.LATITUDE, null))
                service.onStartCommand(Intent(service, MockLocationService::class.java).apply {
                    putExtra(MockLocationService.LATITUDE, "0.0")
                    putExtra(MockLocationService.LONGITUDE, "0.0")
                }, 0, 2)
                // 10 m/s east: the next 1-second fix is projected about 10 m along the heading.
                move("0.0", "0.0", 10f, 450f, 3)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
                var point = fix()[0] as Coordinates
                assertEquals(10f, fix()[1])
                assertEquals(90f, fix()[2])
                assertEquals(0.0, point.latitude, 1e-12)
                assertEquals(10 / 111_319.46, point.longitude, 1e-7)
                assertEquals(point.longitude.toString(), preferences.getString(MockLocationService.LONGITUDE, null))
                // A stop update re-anchors exactly and keeps the last heading at zero speed.
                move("0.0", "0.0002", 0f, 90f, 4)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
                assertEquals(Coordinates(0.0, 0.0002), fix()[0])
                assertEquals(0f, fix()[1])
                assertEquals(90f, fix()[2])
                assertEquals("2.0E-4", preferences.getString(MockLocationService.LONGITUDE, null))
                // Without further updates, movement halts after the timeout instead of drifting forever.
                move("0.0", "0.0", 10f, 0f, 5)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
                point = fix()[0] as Coordinates
                assertEquals(0f, fix()[1])
                assertEquals(20 / 110_574.27, point.latitude, 1e-7)
                move("NaN", "0", 10f, 0f, 6)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
                assertEquals(point, fix()[0])
                assertTrue(MockLocationService.isRunning)
            } finally {
                controller.destroy()
            }
        }
    }

    @Test
    fun rejectsInvalidAndNullRequestsWithAnExplicitError() {
        val controller = Robolectric.buildService(MockLocationService::class.java).create()
        val service = controller.get()
        try {
            val invalid = Intent(service, MockLocationService::class.java).apply {
                putExtra(MockLocationService.LATITUDE, "NaN")
                putExtra(MockLocationService.LONGITUDE, "0")
            }
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(invalid, 0, 1))
            assertFalse(MockLocationService.isRunning)
            val preferences = RuntimeEnvironment.getApplication()
                .getSharedPreferences(MockLocationService.STATE, Context.MODE_PRIVATE)
            assertEquals(service.getString(R.string.invalid_request), preferences.getString(MockLocationService.ERROR, null))
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 2))
            assertFalse(MockLocationService.isRunning)
        } finally {
            controller.destroy()
        }
    }
}
