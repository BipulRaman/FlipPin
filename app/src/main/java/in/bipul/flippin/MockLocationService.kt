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
import android.os.SystemClock
import android.util.Log

class MockLocationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var engine: MockLocationEngine
    private lateinit var state: SharedPreferences
    // Set by Start/Apply or a joystick update; while moving, fixes are projected forward from it.
    private var coordinates: Coordinates? = null
    private var speed = 0f
    private var bearing = 0f
    private var anchoredAt = 0L
    private var reported: Coordinates? = null

    private val tick = object : Runnable {
        override fun run() {
            val current = position() ?: return
            try {
                engine.publish(current, speed, bearing)
                if (current != reported) report(current)
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
        if (intent?.action == ACTION_MOVE) {
            move(intent, startId)
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
            anchor(requested, 0f, 0f)
            reported = requested
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
        liveAnchor = null
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

    private fun move(intent: Intent, startId: Int) {
        if (coordinates == null) {
            // Joystick updates never start a session.
            stopSelf(startId)
            return
        }
        val point = Coordinates.parse(
            intent.getStringExtra(LATITUDE).orEmpty(), intent.getStringExtra(LONGITUDE).orEmpty()
        ) ?: return
        val requestedSpeed = intent.getFloatExtra(SPEED, 0f)
        val requestedBearing = intent.getFloatExtra(BEARING, 0f)
        anchor(
            point,
            if (requestedSpeed.isFinite()) requestedSpeed.toDouble().coerceIn(0.0, MAX_SPEED).toFloat() else 0f,
            if (requestedBearing.isFinite()) (requestedBearing % 360f + 360f) % 360f else 0f
        )
    }

    private fun anchor(point: Coordinates, metersPerSecond: Float, degrees: Float) {
        coordinates = point
        liveAnchor = point
        speed = metersPerSecond
        bearing = degrees
        anchoredAt = SystemClock.elapsedRealtime()
    }

    private fun position(): Coordinates? {
        val anchor = coordinates ?: return null
        if (speed == 0f) return anchor
        val elapsed = SystemClock.elapsedRealtime() - anchoredAt
        if (elapsed <= MOVE_TIMEOUT_MS) return anchor.travelled(bearing.toDouble(), speed * elapsed / 1000.0)
        // The app stopped sending joystick updates (for example, it was killed), so stop moving.
        val halted = anchor.travelled(bearing.toDouble(), speed * MOVE_TIMEOUT_MS / 1000.0)
        anchor(halted, 0f, bearing)
        return halted
    }

    private fun report(point: Coordinates) {
        reported = point
        state.edit()
            .putString(LATITUDE, point.latitude.toString())
            .putString(LONGITUDE, point.longitude.toString())
            .apply()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(point))
    }

    private fun fail(message: String, error: Exception) {
        Log.e(TAG, message, error)
        handler.removeCallbacks(tick)
        coordinates = null
        liveAnchor = null
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
        /** Where the running session was last placed (Start/Apply or a joystick update), shared across screens. */
        @Volatile
        var liveAnchor: Coordinates? = null
            private set
        const val STATE = "session"
        const val RUNNING = "running"
        const val LATITUDE = "latitude"
        const val LONGITUDE = "longitude"
        const val ERROR = "error"
        const val ACTION_MOVE = "in.bipul.flippin.MOVE"
        /** Joystick ground speed in metres per second. */
        const val SPEED = "speed"
        /** Joystick heading in degrees clockwise from true north. */
        const val BEARING = "bearing"
        /** Longest projection without a joystick update; updates arrive every half second while moving. */
        const val MOVE_TIMEOUT_MS = 2_000L
        private const val MAX_SPEED = 100.0 / 3.6
        private const val TAG = "MockLocationService"
        private const val ACTION_STOP = "in.bipul.flippin.STOP"
        private const val CHANNEL = "mock_location"
        private const val NOTIFICATION = 1
    }
}
