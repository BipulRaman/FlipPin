package `in`.bipul.flippin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

class MockLocationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var engine: MockLocationEngine
    private lateinit var state: SharedPreferences
    private var coordinates: Coordinates? = null

    private val tick = object : Runnable {
        override fun run() {
            val current = coordinates ?: return
            try {
                engine.publish(current)
                handler.postDelayed(this, 1_000)
            } catch (error: SecurityException) {
                fail(getString(R.string.mock_permission_revoked), error)
            } catch (error: IllegalArgumentException) {
                fail(getString(R.string.provider_failed, error.message), error)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        state = getSharedPreferences(STATE, MODE_PRIVATE)
        engine = MockLocationEngine(getSystemService(LocationManager::class.java))
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val requested = intent?.let {
            Coordinates.parse(it.getStringExtra(LATITUDE).orEmpty(), it.getStringExtra(LONGITUDE).orEmpty())
        }
        if (requested == null) {
            fail(getString(R.string.invalid_request), IllegalArgumentException("Invalid service request"))
            return START_NOT_STICKY
        }
        try {
            val notification = notification(requested)
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION, notification)
            }
            handler.removeCallbacks(tick)
            engine.start(requested)
            coordinates = requested
            isRunning = true
            state.edit()
                .putBoolean(RUNNING, true)
                .putString(LATITUDE, requested.latitude.toString())
                .putString(LONGITUDE, requested.longitude.toString())
                .remove(ERROR)
                .apply()
            handler.postDelayed(tick, 1_000)
        } catch (error: SecurityException) {
            fail(getString(R.string.mock_permission_revoked), error)
        } catch (error: IllegalArgumentException) {
            fail(getString(R.string.provider_failed, error.message), error)
        } catch (error: IllegalStateException) {
            fail(getString(R.string.start_failed, error.message), error)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        coordinates = null
        isRunning = false
        try {
            engine.stop()
        } catch (error: SecurityException) {
            cleanupFailed(error)
        } catch (error: IllegalArgumentException) {
            cleanupFailed(error)
        }
        state.edit().putBoolean(RUNNING, false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun fail(message: String, error: Exception) {
        Log.e(TAG, message, error)
        handler.removeCallbacks(tick)
        coordinates = null
        isRunning = false
        state.edit().putString(ERROR, message).putBoolean(RUNNING, false).apply()
        stopSelf()
    }

    private fun cleanupFailed(error: Exception) {
        val message = getString(R.string.cleanup_failed, error.message)
        Log.e(TAG, message, error)
        val previous = state.getString(ERROR, null)
        state.edit().putString(ERROR, listOfNotNull(previous, message).joinToString("\n")).apply()
    }

    private fun notification(point: Coordinates): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_pin)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, point.display()))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        var isRunning = false
            private set
        const val STATE = "session"
        const val RUNNING = "running"
        const val LATITUDE = "latitude"
        const val LONGITUDE = "longitude"
        const val ERROR = "error"
        private const val TAG = "MockLocationService"
        private const val ACTION_STOP = "in.bipul.flippin.STOP"
        private const val CHANNEL = "mock_location"
        private const val NOTIFICATION = 1
    }
}
