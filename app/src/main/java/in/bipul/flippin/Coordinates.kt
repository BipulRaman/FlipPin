package `in`.bipul.flippin

import java.util.Locale

data class Coordinates(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Invalid latitude" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Invalid longitude" }
    }

    fun display(): String = String.format(Locale.ROOT, "%.6f, %.6f", latitude, longitude)

    companion object {
        fun parse(latitude: String, longitude: String): Coordinates? {
            val lat = decimal(latitude) ?: return null
            val lon = decimal(longitude) ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return Coordinates(lat, lon)
        }

        fun decimal(value: String): Double? =
            value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}
