package `in`.bipul.gpsemulator

import android.location.Address
import android.location.Geocoder
import android.os.Build
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class PlaceResult(val name: String, val coordinates: Coordinates)

class LocationSearch(
    private val geocoder: Geocoder,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun search(query: String, callback: (Result<List<PlaceResult>>) -> Unit) {
        fun deliver(result: Result<List<PlaceResult>>) {
            if (!closed.get()) callback(result)
        }
        fun found(addresses: List<Address>) {
            val result = try {
                Result.success(addresses.take(5).map { address ->
                    val coordinates = if (address.hasLatitude() && address.hasLongitude()) {
                        Coordinates.parse(address.latitude.toString(), address.longitude.toString())
                    } else null
                    if (coordinates == null) throw IOException("Geocoder returned invalid coordinates")
                    val name = (0..address.maxAddressLineIndex).mapNotNull(address::getAddressLine)
                        .joinToString(", ").ifBlank {
                            listOfNotNull(address.featureName, address.locality, address.countryName)
                                .distinct().joinToString(", ")
                        }.ifBlank { coordinates.display() }
                    PlaceResult(name, coordinates)
                })
            } catch (error: IOException) {
                Result.failure(error)
            }
            deliver(result)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            geocoder.getFromLocationName(query, 5, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) = found(addresses)
                override fun onError(errorMessage: String?) {
                    deliver(Result.failure(IOException(errorMessage ?: "Geocoding service failed")))
                }
            })
        } else {
            executor.execute {
                try {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocationName(query, 5).orEmpty()
                    found(addresses)
                } catch (error: IOException) {
                    deliver(Result.failure(error))
                }
            }
        }
    }

    override fun close() {
        closed.set(true)
        executor.shutdownNow()
    }
}
