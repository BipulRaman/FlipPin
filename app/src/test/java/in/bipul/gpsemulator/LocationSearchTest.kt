package `in`.bipul.gpsemulator

import android.location.Address
import android.location.Geocoder
import android.os.Build
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class LocationSearchTest {
    @Test
    fun findsNamedPlacesWithExactCoordinatesAndHandlesNoMatches() {
        val geocoder = mock(Geocoder::class.java)
        val places = mutableListOf(Address(Locale.ENGLISH).apply {
            latitude = 48.858412345
            longitude = 2.294512345
            setAddressLine(0, "Eiffel Tower, Paris, France")
        })
        stub(geocoder, places)
        val executor = Executors.newSingleThreadExecutor()
        val search = LocationSearch(geocoder, executor)
        try {
            var result: Result<List<PlaceResult>>? = null
            search.search("Paris") { result = it }
            executor.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(
                PlaceResult("Eiffel Tower, Paris, France", Coordinates(48.858412345, 2.294512345)),
                result!!.getOrThrow().single()
            )
            places.clear()
            search.search("Paris") { result = it }
            executor.submit {}.get(5, TimeUnit.SECONDS)
            assertTrue(result!!.getOrThrow().isEmpty())
        } finally {
            search.close()
        }
    }

    @Test
    fun reportsProviderFailuresAndRejectsMissingCoordinates() {
        val geocoder = mock(Geocoder::class.java)
        if (Build.VERSION.SDK_INT >= 33) {
            doAnswer {
                it.getArgument<Geocoder.GeocodeListener>(2).onError("Provider unavailable")
                null
            }.`when`(geocoder).getFromLocationName(eq("Paris"), eq(5), any(Geocoder.GeocodeListener::class.java))
        } else {
            @Suppress("DEPRECATION")
            `when`(geocoder.getFromLocationName("Paris", 5)).thenThrow(IOException("Provider unavailable"))
        }
        val executor = Executors.newSingleThreadExecutor()
        val search = LocationSearch(geocoder, executor)
        try {
            var result: Result<List<PlaceResult>>? = null
            search.search("Paris") { result = it }
            executor.submit {}.get(5, TimeUnit.SECONDS)
            assertTrue(result!!.exceptionOrNull() is IOException)
            stub(geocoder, mutableListOf(Address(Locale.ENGLISH)))
            search.search("Paris") { result = it }
            executor.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals("Geocoder returned invalid coordinates", result!!.exceptionOrNull()?.message)
        } finally {
            search.close()
        }
    }

    @Test
    @Config(sdk = [35])
    fun ignoresDelayedResponsesAfterClosing() {
        val geocoder = mock(Geocoder::class.java)
        var listener: Geocoder.GeocodeListener? = null
        doAnswer {
            listener = it.getArgument(2)
            null
        }.`when`(geocoder).getFromLocationName(eq("Paris"), eq(5), any(Geocoder.GeocodeListener::class.java))
        val search = LocationSearch(geocoder)
        var called = false
        search.search("Paris") { called = true }
        search.close()
        listener!!.onGeocode(mutableListOf())
        assertFalse(called)
    }

    private fun stub(geocoder: Geocoder, places: MutableList<Address>) {
        if (Build.VERSION.SDK_INT >= 33) {
            doAnswer {
                it.getArgument<Geocoder.GeocodeListener>(2).onGeocode(places)
                null
            }.`when`(geocoder).getFromLocationName(eq("Paris"), eq(5), any(Geocoder.GeocodeListener::class.java))
        } else {
            @Suppress("DEPRECATION")
            org.mockito.Mockito.doReturn(places).`when`(geocoder).getFromLocationName("Paris", 5)
        }
    }
}
