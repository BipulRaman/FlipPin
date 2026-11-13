package `in`.bipul.flippin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.AttributeSet
import android.util.AndroidRuntimeException
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.math.round

class CoordinateMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    var onCoordinateChanged: ((Coordinates) -> Unit)? = null
    var onTileFailureChanged: ((Boolean) -> Unit)? = null
    var scale: PinScale = PinScale.WORLD
        private set
    internal var webView: WebView? = null
        private set
    private var point: Coordinates? = null
    private var cameraLatitude = 20.0
    private var cameraLongitude = 0.0
    private var zoom = 2.0
    private var generation = 0
    private var revision = 0
    private var ready = false
    private var online = false
    private var destroyed = false
    private var handlingPick = false

    init {
        isFocusable = true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() = WebView(context).apply {
        setBackgroundColor(Color.rgb(226, 237, 238))
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        addJavascriptInterface(MapBridge(), "FlipPin")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (uri.scheme == "https" && uri.host == ASSET_HOST && uri.port == -1) {
                    val asset = ASSETS[uri.path] ?: return blockedResponse()
                    val type = when {
                        asset.endsWith(".html") -> "text/html"
                        asset.endsWith(".css") -> "text/css"
                        else -> "text/javascript"
                    }
                    return WebResourceResponse(type, "UTF-8", context.assets.open(asset)).apply {
                        responseHeaders = mapOf(
                            "Content-Security-Policy" to CSP,
                            "X-Content-Type-Options" to "nosniff"
                        )
                    }
                }
                if (uri.scheme == "https" && uri.host == "tiles.openfreemap.org" &&
                    uri.userInfo == null && (uri.port == -1 || uri.port == 443)
                ) return null
                Log.w(TAG, "Blocked unexpected map resource origin: ${uri.scheme}://${uri.host}")
                return blockedResponse()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.e(TAG, "Map WebView renderer exited; crashed=${detail.didCrash()}")
                removeView(view)
                view.destroy()
                webView = null
                ready = false
                onTileFailureChanged?.invoke(true)
                return true
            }
        }
    }

    fun setOnline(available: Boolean) {
        if (destroyed || online == available) return
        online = available
        if (online) {
            retryTiles()
        } else {
            generation++
            ready = false
            disposeWebView()
            onTileFailureChanged?.invoke(false)
        }
    }

    fun setCoordinates(coordinates: Coordinates?, recenter: Boolean = true) {
        point = coordinates
        if (recenter && coordinates != null) {
            cameraLatitude = coordinates.latitude.coerceIn(-MAP_LATITUDE_LIMIT, MAP_LATITUDE_LIMIT)
            cameraLongitude = coordinates.longitude
        }
        contentDescription = context.getString(
            R.string.pad_accessibility, coordinates?.display() ?: context.getString(R.string.pad_empty)
        )
        if (!handlingPick) sendState(recenter && coordinates != null)
    }

    fun setScale(newScale: PinScale) {
        scale = newScale
        zoom = when (scale) {
            PinScale.WORLD -> 2.0
            PinScale.AREA -> 13.0
            PinScale.FINE -> 18.0
        }
        point?.let {
            cameraLatitude = it.latitude.coerceIn(-MAP_LATITUDE_LIMIT, MAP_LATITUDE_LIMIT)
            cameraLongitude = it.longitude
        }
        sendState(moveCamera = true)
    }

    fun retryTiles() {
        if (destroyed || !online) return
        ready = false
        generation++
        onTileFailureChanged?.invoke(false)
        val renderer = webView ?: try {
            createWebView().also {
                webView = it
                addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
        } catch (error: AndroidRuntimeException) {
            Log.e(TAG, "Android System WebView is unavailable", error)
            onTileFailureChanged?.invoke(true)
            return
        }
        renderer.loadUrl("https://$ASSET_HOST/map/index.html?generation=$generation")
    }

    private fun sendState(moveCamera: Boolean) {
        revision++
        if (!ready || !online || destroyed) return
        val state = JSONObject().put("revision", revision)
        state.put("point", point?.takeIf { it.latitude in -MAP_LATITUDE_LIMIT..MAP_LATITUDE_LIMIT }?.let {
            JSONObject().put("latitude", it.latitude).put("longitude", it.longitude)
        } ?: JSONObject.NULL)
        if (moveCamera) {
            state.put("camera", JSONObject().put("latitude", cameraLatitude)
                .put("longitude", cameraLongitude).put("zoom", zoom))
        }
        webView?.evaluateJavascript("window.FlipPinMap.setState($state)", null)
    }

    internal inner class MapBridge {
        @JavascriptInterface
        fun postMessage(message: String) {
            post {
                if (destroyed || !online || message.length > 2048) return@post
                try {
                    val event = JSONObject(message)
                    if (event.getInt("generation") != generation) return@post
                    when (event.getString("type")) {
                        "ready" -> {
                            ready = true
                            sendState(moveCamera = true)
                        }
                        "error" -> {
                            Log.w(TAG, "Map renderer: ${event.optString("message").take(500)}")
                            onTileFailureChanged?.invoke(true)
                        }
                        "loaded" -> onTileFailureChanged?.invoke(false)
                        "pick", "camera" -> {
                            if (!ready || event.getInt("revision") != revision) return@post
                            val lat = event.getDouble("latitude")
                            val lon = event.getDouble("longitude")
                            require(lat.isFinite() && lat in -MAP_LATITUDE_LIMIT..MAP_LATITUDE_LIMIT)
                            require(lon.isFinite())
                            val longitude = wrapLongitude(lon)
                            if (event.getString("type") == "pick") {
                                val selected = Coordinates(round(lat * 1e6) / 1e6, round(longitude * 1e6) / 1e6)
                                handlingPick = true
                                try {
                                    setCoordinates(selected, recenter = false)
                                    onCoordinateChanged?.invoke(selected)
                                } finally {
                                    handlingPick = false
                                }
                            } else {
                                val newZoom = event.getDouble("zoom")
                                require(newZoom.isFinite() && newZoom in 0.0..22.0)
                                cameraLatitude = lat
                                cameraLongitude = longitude
                                zoom = newZoom
                            }
                        }
                        else -> throw IllegalArgumentException("Unknown map event")
                    }
                } catch (error: JSONException) {
                    Log.w(TAG, "Invalid map event", error)
                    onTileFailureChanged?.invoke(true)
                } catch (error: IllegalArgumentException) {
                    Log.w(TAG, "Invalid map coordinates or event", error)
                    onTileFailureChanged?.invoke(true)
                }
            }
        }
    }

    fun resumeMap() {
        if (!destroyed) webView?.onResume()
    }

    fun pauseMap() {
        if (!destroyed) webView?.onPause()
    }

    fun destroyMap() {
        destroyed = true
        ready = false
        disposeWebView()
    }

    private fun disposeWebView() {
        webView?.let {
            removeView(it)
            it.removeJavascriptInterface("FlipPin")
            it.stopLoading()
            it.destroy()
        }
        webView = null
    }

    fun saveCamera(state: Bundle) {
        state.putDouble("map_latitude", cameraLatitude)
        state.putDouble("map_longitude", cameraLongitude)
        state.putDouble("map_zoom", zoom)
    }

    fun restoreCamera(state: Bundle) {
        if (!state.containsKey("map_zoom")) return
        val latitude = state.getDouble("map_latitude")
        val longitude = state.getDouble("map_longitude")
        val restoredZoom = state.getDouble("map_zoom")
        if (!latitude.isFinite() || latitude !in -MAP_LATITUDE_LIMIT..MAP_LATITUDE_LIMIT ||
            !longitude.isFinite() || longitude !in -180.0..180.0 ||
            !restoredZoom.isFinite() || restoredZoom !in 0.0..22.0
        ) {
            Log.w(TAG, "Ignoring invalid saved map camera")
            return
        }
        cameraLatitude = latitude
        cameraLongitude = longitude
        zoom = restoredZoom
        sendState(moveCamera = true)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val current = point ?: return super.onKeyDown(keyCode, event)
        val step = scale.step
        val selected = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> current.nudged(step, 0.0)
            KeyEvent.KEYCODE_DPAD_DOWN -> current.nudged(-step, 0.0)
            KeyEvent.KEYCODE_DPAD_LEFT -> current.nudged(0.0, -step)
            KeyEvent.KEYCODE_DPAD_RIGHT -> current.nudged(0.0, step)
            else -> return super.onKeyDown(keyCode, event)
        }
        setCoordinates(selected)
        onCoordinateChanged?.invoke(selected)
        return true
    }

    private fun blockedResponse() = WebResourceResponse(
        "text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0))
    )

    companion object {
        const val MAP_LATITUDE_LIMIT = 85.0511287798066
        private const val TAG = "CoordinateMap"
        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val CSP = "default-src 'none'; script-src 'self'; " +
            "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; " +
            "connect-src https://tiles.openfreemap.org; worker-src 'self'; child-src 'self';"
        private val ASSETS = listOf(
            "map/index.html", "map/map.js", "map/map.css", "map/labels.mjs",
            "map/vendor/maplibre-gl.mjs", "map/vendor/maplibre-gl-worker.mjs", "map/vendor/maplibre-gl.css"
        ).associateBy { "/$it" }

        private fun wrapLongitude(value: Double) = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    }
}
