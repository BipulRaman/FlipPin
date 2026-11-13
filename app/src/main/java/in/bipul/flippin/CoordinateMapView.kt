package `in`.bipul.flippin

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File
import kotlin.math.round

class CoordinateMapView @JvmOverloads constructor(
    context: Context, attributes: AttributeSet? = null
) : FrameLayout(context, attributes) {
    val map: MapView
    private val marker: Marker
    private var point: Coordinates? = null
    var onCoordinateChanged: ((Coordinates) -> Unit)? = null
    var onTileFailureChanged: ((Boolean) -> Unit)? = null
    var scale = PinScale.WORLD
        private set
    private val tileHandler = Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            MapTileProviderBase.MAPTILE_FAIL_ID -> {
                Log.w("FlipPinMap", "A map tile could not be loaded")
                onTileFailureChanged?.invoke(true)
            }
        }
        false
    }

    init {
        Configuration.getInstance().apply {
            userAgentValue = "FlipPin/${BuildConfig.VERSION_NAME} (+https://github.com/BipulRaman/FlipPin)"
            osmdroidBasePath = File(context.filesDir, "map")
            osmdroidTileCache = File(context.cacheDir, "map-tiles")
            // osmdroid reads server cache headers; retain tiles for at least seven days as well.
            expirationExtendedDuration = 7L * 24 * 60 * 60 * 1000
            tileFileSystemCacheMaxBytes = 100L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 80L * 1024 * 1024
        }
        map = MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            minZoomLevel = 2.0
            maxZoomLevel = 19.0
            controller.setZoom(2.0)
            controller.setCenter(GeoPoint(0.0, 0.0))
            tileProvider.tileRequestCompleteHandlers.add(tileHandler)
        }
        addView(map, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(position: GeoPoint): Boolean {
                choose(position)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                return true
            }

            override fun longPressHelper(position: GeoPoint): Boolean = false
        }))
        marker = Marker(map).apply {
            icon = context.getDrawable(R.drawable.map_pin)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            isDraggable = true
            isEnabled = false
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ -> true }
            setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                override fun onMarkerDragStart(marker: Marker) = Unit
                override fun onMarkerDrag(marker: Marker) = choose(marker.position)
                override fun onMarkerDragEnd(marker: Marker) {
                    choose(marker.position)
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            })
        }
        map.overlays.add(marker)
        isFocusable = true
        updateDescription()
    }

    fun setCoordinates(coordinates: Coordinates?, recenter: Boolean = true) {
        point = coordinates
        marker.isEnabled = coordinates != null && coordinates.latitude in -MAP_LATITUDE_LIMIT..MAP_LATITUDE_LIMIT
        coordinates?.let {
            marker.position = GeoPoint(it.latitude, it.longitude)
            if (recenter) map.controller.setCenter(cameraPoint(it))
        }
        updateDescription()
        map.invalidate()
    }

    fun setScale(newScale: PinScale) {
        scale = newScale
        map.controller.setZoom(when (scale) {
            PinScale.WORLD -> 2.0
            PinScale.AREA -> 13.0
            PinScale.FINE -> 18.0
        })
        point?.let { map.controller.setCenter(cameraPoint(it)) }
    }

    fun resumeMap() = map.onResume()

    fun pauseMap() = map.onPause()

    fun destroyMap() {
        map.tileProvider.tileRequestCompleteHandlers.remove(tileHandler)
        tileHandler.removeCallbacksAndMessages(null)
        map.onDetach()
    }

    fun retryTiles() {
        onTileFailureChanged?.invoke(false)
        map.invalidate()
    }

    fun saveCamera(state: Bundle) {
        state.putDouble("map_latitude", map.mapCenter.latitude)
        state.putDouble("map_longitude", map.mapCenter.longitude)
        state.putDouble("map_zoom", map.zoomLevelDouble)
    }

    fun restoreCamera(state: Bundle) {
        if (!state.containsKey("map_zoom")) return
        map.controller.setZoom(state.getDouble("map_zoom"))
        map.controller.setCenter(GeoPoint(state.getDouble("map_latitude"), state.getDouble("map_longitude")))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val current = point ?: return super.onKeyDown(keyCode, event)
        val adjusted = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> current.nudged(scale.step, 0.0)
            KeyEvent.KEYCODE_DPAD_DOWN -> current.nudged(-scale.step, 0.0)
            KeyEvent.KEYCODE_DPAD_LEFT -> current.nudged(0.0, -scale.step)
            KeyEvent.KEYCODE_DPAD_RIGHT -> current.nudged(0.0, scale.step)
            else -> return super.onKeyDown(keyCode, event)
        }
        setCoordinates(adjusted)
        onCoordinateChanged?.invoke(adjusted)
        return true
    }

    private fun choose(position: GeoPoint) {
        val longitude = ((position.longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        val selected = Coordinates(
            round(position.latitude.coerceIn(-MAP_LATITUDE_LIMIT, MAP_LATITUDE_LIMIT) * 1e6) / 1e6,
            round(longitude * 1e6) / 1e6
        )
        setCoordinates(selected, recenter = false)
        onCoordinateChanged?.invoke(selected)
    }

    private fun cameraPoint(coordinates: Coordinates) = GeoPoint(
        coordinates.latitude.coerceIn(-MAP_LATITUDE_LIMIT, MAP_LATITUDE_LIMIT), coordinates.longitude
    )

    private fun updateDescription() {
        contentDescription = context.getString(
            R.string.pad_accessibility, point?.display() ?: context.getString(R.string.pad_empty)
        )
    }

    companion object {
        const val MAP_LATITUDE_LIMIT = 85.0511287798066
    }
}
