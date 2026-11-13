package `in`.bipul.flippin

import android.content.Context
import android.graphics.PointF
import android.os.Bundle
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Projection
import org.maplibre.android.maps.Style
import org.maplibre.android.maps.UiSettings
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.android.style.layers.SymbolLayer
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowViewGroup

@Implements(MapLibre::class)
class ShadowMapLibre {
    companion object {
        @JvmStatic
        @Implementation
        fun getInstance(context: Context): MapLibre = mock(MapLibre::class.java)
    }
}

@Suppress("DEPRECATION")
@Implements(MapView::class)
class ShadowVectorMap : ShadowViewGroup() {
    val controller: MapLibreMap = mock(MapLibreMap::class.java)
    var camera: CameraPosition = CameraPosition.Builder().target(LatLng(0.0, 0.0)).zoom(2.0).build()
    var marker: Marker? = null
    var click: MapLibreMap.OnMapClickListener? = null
    var longClick: MapLibreMap.OnMapLongClickListener? = null
    var failure: MapView.OnDidFailLoadingMapListener? = null
    val labelUpdates = mutableMapOf<String, Expression>()

    @Implementation
    fun initialize(context: Context, options: MapLibreMapOptions) {
        val projection = mock(Projection::class.java)
        val settings = mock(UiSettings::class.java)
        val style = mock(Style::class.java)
        val labels = listOf("place" to Expression.get("name"), "shield" to Expression.get("ref")).map { (id, field) ->
            mock(SymbolLayer::class.java).also { layer ->
                doReturn(PropertyFactory.textField(field)).`when`(layer).textField
                doAnswer {
                    labelUpdates[id] = it.getArgument<PropertyValue<*>>(0).expression!!
                    null
                }.`when`(layer).setProperties(any(PropertyValue::class.java))
            }
        }
        `when`(style.layers).thenReturn(labels)
        `when`(controller.style).thenReturn(style)
        `when`(controller.projection).thenReturn(projection)
        `when`(controller.uiSettings).thenReturn(settings)
        `when`(controller.cameraPosition).thenAnswer { camera }
        doAnswer { camera = it.getArgument(0); null }.`when`(controller).setCameraPosition(any(CameraPosition::class.java))
        doAnswer { camera = requireNotNull(it.getArgument<CameraUpdate>(0).getCameraPosition(controller)); null }
            .`when`(controller).moveCamera(any(CameraUpdate::class.java))
        doAnswer { click = it.getArgument(0); null }
            .`when`(controller).addOnMapClickListener(any(MapLibreMap.OnMapClickListener::class.java))
        doAnswer { longClick = it.getArgument(0); null }
            .`when`(controller).addOnMapLongClickListener(any(MapLibreMap.OnMapLongClickListener::class.java))
        doAnswer {
            it.getArgument<Style.OnStyleLoaded>(1).onStyleLoaded(style)
            null
        }.`when`(controller).setStyle(any(Style.Builder::class.java), any(Style.OnStyleLoaded::class.java))
        `when`(projection.toScreenLocation(any(LatLng::class.java))).thenAnswer {
            val point = it.getArgument<LatLng>(0)
            PointF(((point.longitude - 45) * 10000).toFloat(), ((point.latitude - 12) * 10000).toFloat())
        }
        `when`(projection.fromScreenLocation(any(PointF::class.java))).thenAnswer {
            val point = it.getArgument<PointF>(0)
            LatLng(12 + point.y / 10000.0, 45 + point.x / 10000.0)
        }
        `when`(controller.addMarker(any(MarkerOptions::class.java))).thenAnswer {
            var position = it.getArgument<MarkerOptions>(0).position
            mock(Marker::class.java).also { created ->
                `when`(created.position).thenAnswer { position }
                doAnswer { call -> position = call.getArgument(0); null }
                    .`when`(created).setPosition(any(LatLng::class.java))
                marker = created
            }
        }
        doAnswer { marker = null; null }.`when`(controller).removeMarker(any(Marker::class.java))
    }

    @Implementation
    fun getMapAsync(callback: OnMapReadyCallback) = callback.onMapReady(controller)

    @Implementation
    fun addOnDidFailLoadingMapListener(listener: MapView.OnDidFailLoadingMapListener) { failure = listener }

    @Implementation
    fun onCreate(state: Bundle?) = Unit
    @Implementation
    fun onStart() = Unit
    @Implementation
    fun onResume() = Unit
    @Implementation
    fun onPause() = Unit
    @Implementation
    fun onStop() = Unit
    @Implementation
    fun onDestroy() = Unit
    @Implementation
    fun onLowMemory() = Unit
}
