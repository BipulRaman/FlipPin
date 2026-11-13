package `in`.bipul.flippin

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.cos

/** Directional-nudge distance per tap, about 3.6 s of travel at a typical speed for each pace. */
enum class Pace(val meters: Int, val kilometersPerHour: Int, val zoom: Double) {
    WALK(5, 5, 18.0),
    RUN(10, 10, 17.0),
    DRIVE(50, 50, 15.0);

    val secondsPerTap: Double get() = meters * 3.6 / kilometersPerHour
}

fun Coordinates.moved(northMeters: Double, eastMeters: Double): Coordinates {
    require(northMeters.isFinite() && eastMeters.isFinite()) { "Movements must be finite" }
    val phi = Math.toRadians(latitude)
    // WGS 84 length of one degree of latitude and longitude at this latitude.
    val latitudeDegree = 111_132.92 - 559.82 * cos(2 * phi) + 1.175 * cos(4 * phi) - 0.0023 * cos(6 * phi)
    val longitudeDegree = 111_412.84 * cos(phi) - 93.5 * cos(3 * phi) + 0.118 * cos(5 * phi)
    // East and west are undefined exactly at a pole.
    val east = if (longitudeDegree > 1e-6) (eastMeters / longitudeDegree) % 360.0 else 0.0
    return nudged(roundedDegrees(northMeters / latitudeDegree), roundedDegrees(east))
}

fun Coordinates.nudged(north: Double, east: Double): Coordinates {
    require(north.isFinite() && east.isFinite()) { "Adjustments must be finite" }
    return Coordinates(
        BigDecimal.valueOf(latitude).add(BigDecimal.valueOf(north)).toDouble().coerceIn(-90.0, 90.0),
        wrapLongitude(BigDecimal.valueOf(longitude).add(BigDecimal.valueOf(east)))
    )
}

// Seven decimals (about 1 cm) keep repeated movements readable in the coordinate fields.
private fun roundedDegrees(value: Double): Double =
    BigDecimal.valueOf(value).setScale(7, RoundingMode.HALF_EVEN).toDouble()

private fun wrapLongitude(value: BigDecimal): Double {
    val fullTurn = BigDecimal.valueOf(360)
    val remainder = value.remainder(fullTurn)
    return when {
        remainder > BigDecimal.valueOf(180) -> remainder.subtract(fullTurn)
        remainder < BigDecimal.valueOf(-180) -> remainder.add(fullTurn)
        else -> remainder
    }.toDouble()
}
