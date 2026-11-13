package `in`.bipul.flippin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import kotlin.math.hypot
import kotlin.math.round

@Suppress("DEPRECATION")
class CoordinateMapView @JvmOverloads constructor(
    context: Context, attributes: AttributeSet? = null
) : FrameLayout(context, attributes) {
    val mapView: MapView
    var map: MapLibreMap? = null
        private set
    private var marker: Marker? = null
    private var point: Coordinates? = null
    private var camera = CameraPosition.Builder().target(LatLng(0.0, 0.0)).zoom(2.0).build()
    private var dragging = false
    private var destroyed = false
    var onCoordinateChanged: ((Coordinates) -> Unit)? = null
    var onTileFailureChanged: ((Boolean) -> Unit)? = null
    var scale = PinScale.WORLD
        private set

    init {
        MapLibre.getInstance(context.applicationContext)
        mapView = MapView(context, MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true).attributionEnabled(false).logoEnabled(false)
            .rotateGesturesEnabled(false).tiltGesturesEnabled(false))
        addView(mapView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        mapView.onCreate(null)
        mapView.addOnDidFailLoadingMapListener { message ->
            Log.w("FlipPinMap", "Map loading failed: $message")
            onTileFailureChanged?.invoke(true)
        }
        mapView.getMapAsync { ready ->
            if (!destroyed) {
                map = ready
                ready.setMinZoomPreference(2.0)
                ready.setMaxZoomPreference(19.0)
                ready.cameraPosition = camera
                ready.addOnCameraIdleListener { camera = ready.cameraPosition }
                ready.addOnMapClickListener {
                    choose(it)
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    true
                }
                ready.addOnMapLongClickListener { position ->
                    val selected = point
                    if (selected == null || marker == null) false
                    else {
                        val pin = ready.projection.toScreenLocation(LatLng(selected.latitude, selected.longitude))
                        val touch = ready.projection.toScreenLocation(position)
                        dragging = hypot(pin.x - touch.x, pin.y - touch.y) <= 40 * resources.displayMetrics.density
                        if (dragging) {
                            ready.uiSettings.isScrollGesturesEnabled = false
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                        dragging
                    }
                }
                loadStyle()
            }
        }
        isFocusable = true
        updateDescription()
    }

    private fun loadStyle() {
        map?.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
            // Replace name labels, not route shields, with English/Latin-only fields.
            style.layers.filterIsInstance<SymbolLayer>().forEach { layer ->
                val field = layer.textField
                val text = field.expression?.toString() ?: field.value?.toString().orEmpty()
                if (text.contains("name")) {
                    layer.setProperties(PropertyFactory.textField(Expression.switchCase(
                        Expression.neq(Expression.coalesce(Expression.get("name:en"), Expression.literal("")), Expression.literal("")),
                        Expression.get("name:en"),
                        Expression.neq(Expression.coalesce(Expression.get("name_en"), Expression.literal("")), Expression.literal("")),
                        Expression.get("name_en"),
                        Expression.coalesce(Expression.get("name:latin"), Expression.literal(""))
                    )))
                }
            }
            updateMarker()
            onTileFailureChanged?.invoke(false)
        }
    }

    fun setCoordinates(coordinates: Coordinates?, recenter: Boolean = true) {
        point = coordinates
        if (recenter && coordinates != null) {
            camera = CameraPosition.Builder(currentCamera()).target(cameraPoint(coordinates)).build()
            map?.moveCamera(CameraUpdateFactory.newCameraPosition(camera))
        }
        updateMarker()
        updateDescription()
    }

    fun setScale(newScale: PinScale) {
        scale = newScale
        camera = CameraPosition.Builder(currentCamera()).zoom(when (scale) {
            PinScale.WORLD -> 2.0
            PinScale.AREA -> 13.0
            PinScale.FINE -> 18.0
        }).apply { point?.let { target(cameraPoint(it)) } }.build()
        map?.moveCamera(CameraUpdateFactory.newCameraPosition(camera))
    }

    fun resumeMap() {
        mapView.onStart()
        mapView.onResume()
    }

    fun pauseMap() {
        endDrag()
        mapView.onPause()
        mapView.onStop()
    }

    fun destroyMap() {
        destroyed = true
        mapView.onDestroy()
    }

    fun lowMemory() = mapView.onLowMemory()

    fun retryTiles() {
        onTileFailureChanged?.invoke(false)
        loadStyle()
    }

    fun saveCamera(state: Bundle) {
        val saved = currentCamera()
        saved.target?.let {
            state.putDouble("map_latitude", it.latitude)
            state.putDouble("map_longitude", it.longitude)
        }
        state.putDouble("map_zoom", saved.zoom)
    }

    fun restoreCamera(state: Bundle) {
        if (!state.containsKey("map_zoom")) return
        camera = CameraPosition.Builder().target(LatLng(
            state.getDouble("map_latitude"), state.getDouble("map_longitude")
        )).zoom(state.getDouble("map_zoom")).build()
        map?.moveCamera(CameraUpdateFactory.newCameraPosition(camera))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        if (dragging) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) {
                map?.projection?.fromScreenLocation(PointF(event.x, event.y))?.let(::choose)
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                endDrag()
                val cancel = MotionEvent.obtain(event)
                cancel.action = MotionEvent.ACTION_CANCEL
                mapView.dispatchTouchEvent(cancel)
                cancel.recycle()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
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

    private fun currentCamera() = map?.cameraPosition ?: camera

    private fun endDrag() {
        if (dragging) map?.uiSettings?.isScrollGesturesEnabled = true
        dragging = false
    }

    private fun updateMarker() {
        val ready = map ?: return
        val selected = point
        if (selected == null || selected.latitude !in -MAP_LATITUDE_LIMIT..MAP_LATITUDE_LIMIT) {
            marker?.let { ready.removeMarker(it) }
            marker = null
            return
        }
        val position = LatLng(selected.latitude, selected.longitude)
        val current = marker
        if (current != null) {
            current.position = position
            ready.updateMarker(current)
        } else if (ready.style != null) {
            val drawable = context.getDrawable(R.drawable.map_pin)!!
            val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
            marker = ready.addMarker(MarkerOptions().position(position)
                .icon(IconFactory.getInstance(context).fromBitmap(bitmap)))
            ready.setOnMarkerClickListener { true }
        }
    }

    private fun choose(position: LatLng) {
        val longitude = ((position.longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        val selected = Coordinates(
            round(position.latitude.coerceIn(-MAP_LATITUDE_LIMIT, MAP_LATITUDE_LIMIT) * 1e6) / 1e6,
            round(longitude * 1e6) / 1e6
        )
        setCoordinates(selected, recenter = false)
        onCoordinateChanged?.invoke(selected)
    }

    private fun cameraPoint(coordinates: Coordinates) = LatLng(
        coordinates.latitude.coerceIn(-MAP_LATITUDE_LIMIT, MAP_LATITUDE_LIMIT), coordinates.longitude
    )

    private fun updateDescription() {
        contentDescription = context.getString(
            R.string.pad_accessibility, point?.display() ?: context.getString(R.string.pad_empty)
        )
    }

    companion object {
        const val MAP_LATITUDE_LIMIT = 85.0511287798066
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    }
}
