package `in`.bipul.flippin

import android.annotation.SuppressLint
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import android.util.Log

class MockLocationEngine(private val manager: LocationManager) {
    private val installed = mutableListOf<String>()

    fun start(coordinates: Coordinates) {
        if (installed.isEmpty()) {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                install(provider)
                installed.add(provider)
                manager.setTestProviderEnabled(provider, true)
            }
        }
        publish(coordinates)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("WrongConstant")
    private fun install(provider: String) {
        if (Build.VERSION.SDK_INT >= 31) {
            manager.addTestProvider(provider, ProviderProperties.Builder()
                .setHasAltitudeSupport(true)
                .setHasSpeedSupport(true)
                .setHasBearingSupport(true)
                .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                .setAccuracy(ProviderProperties.ACCURACY_FINE)
                .build())
        } else {
            // ProviderProperties constants are unavailable on Android 8-11.
            manager.addTestProvider(
                provider, false, false, false, false, true, true, true,
                Criteria.POWER_LOW, Criteria.ACCURACY_FINE
            )
        }
    }

    fun publish(coordinates: Coordinates, speed: Float = 0f, bearing: Float = 0f) {
        check(installed.isNotEmpty()) { "Mock providers have not been started" }
        for (provider in installed) {
            manager.setTestProviderLocation(provider, Location(provider).apply {
                latitude = coordinates.latitude
                longitude = coordinates.longitude
                accuracy = 10f
                altitude = 0.0
                this.speed = speed
                this.bearing = bearing
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            })
        }
    }

    fun stop() {
        var failure: RuntimeException? = null
        for (provider in installed.toList().asReversed()) {
            try {
                manager.removeTestProvider(provider)
            } catch (error: SecurityException) {
                Log.e(TAG, "Mock permission revoked while removing $provider", error)
                failure = failure ?: error
            } catch (error: IllegalArgumentException) {
                Log.e(TAG, "Cannot remove mock provider $provider", error)
                failure = failure ?: error
            }
        }
        installed.clear()
        failure?.let { throw it }
    }

    companion object {
        private const val TAG = "MockLocationEngine"
    }
}
