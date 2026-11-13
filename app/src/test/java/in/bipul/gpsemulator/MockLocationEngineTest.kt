package `in`.bipul.gpsemulator

import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MockLocationEngineTest {
    private val manager = mock(LocationManager::class.java)

    @Suppress("MissingPermission")
    @Test
    fun publishesBothProvidersAndRefreshesCoordinatesAndTimestamps() {
        val engine = MockLocationEngine(manager)
        engine.start(Coordinates(12.5, -45.25))
        val locations = ArgumentCaptor.forClass(Location::class.java)
        verify(manager, times(2)).setTestProviderLocation(anyString(), locations.capture())
        assertEquals(listOf("gps", "network"), locations.allValues.map { it.provider })
        for (location in locations.allValues) {
            assertEquals(12.5, location.latitude, 0.0)
            assertEquals(-45.25, location.longitude, 0.0)
            assertEquals(10f, location.accuracy)
            assertTrue(location.time > 0)
        }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        engine.start(Coordinates(-90.0, 180.0))
        val updated = ArgumentCaptor.forClass(Location::class.java)
        verify(manager, times(4)).setTestProviderLocation(anyString(), updated.capture())
        for (location in updated.allValues.takeLast(2)) {
            assertEquals(-90.0, location.latitude, 0.0)
            assertEquals(180.0, location.longitude, 0.0)
            assertEquals(SystemClock.elapsedRealtimeNanos(), location.elapsedRealtimeNanos)
        }
        engine.stop()
        engine.stop()
        verify(manager, times(1)).removeTestProvider("gps")
        verify(manager, times(1)).removeTestProvider("network")
    }

    @Test
    fun cleanupAttemptsBothProvidersEvenWhenPermissionIsRevoked() {
        val engine = MockLocationEngine(manager)
        engine.start(Coordinates(0.0, 0.0))
        doThrow(SecurityException("Revoked")).`when`(manager).removeTestProvider("network")
        var failed = false
        try {
            engine.stop()
        } catch (_: SecurityException) {
            failed = true
        }
        assertTrue(failed)
        verify(manager).removeTestProvider("gps")
        verify(manager).removeTestProvider("network")
        engine.stop()
    }

    @Test
    fun partialStartupCanBeCleanedUp() {
        val engine = MockLocationEngine(manager)
        doThrow(SecurityException("Revoked")).`when`(manager).setTestProviderEnabled("network", true)
        var started = false
        try {
            engine.start(Coordinates(0.0, 0.0))
            started = true
        } catch (_: SecurityException) {
            engine.stop()
        }
        assertFalse(started)
        verify(manager).removeTestProvider("gps")
        verify(manager).removeTestProvider("network")
    }
}
