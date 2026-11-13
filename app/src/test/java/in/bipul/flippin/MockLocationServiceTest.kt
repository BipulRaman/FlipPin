package `in`.bipul.flippin

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
