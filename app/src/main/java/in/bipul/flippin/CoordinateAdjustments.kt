package `in`.bipul.flippin

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.cos
import kotlin.math.sin

/** Distance of one keyboard or accessibility movement step: a few walking paces. */
const val STEP_METERS = 5.0

enum class MovementSpeed(val label: Int, val kilometersPerHour: Double) {
    WALK(R.string.speed_walk, 5.0),
    RUN(R.string.speed_run, 10.0),
    CYCLE(R.string.speed_cycle, 20.0),
    DRIVE(R.string.speed_drive, 50.0)
}

fun Coordinates.moved(northMeters: Double, eastMeters: Double): Coordinates {
    require(northMeters.isFinite() && eastMeters.isFinite()) { "Movements must be finite" }
    val (north, east) = degreeOffsets(northMeters, eastMeters)
    return nudged(roundedDegrees(north), roundedDegrees(east))
}

/** Exact position after travelling [meters] on [bearing] degrees clockwise from true north. */
fun Coordinates.travelled(bearing: Double, meters: Double): Coordinates {
    require(bearing.isFinite() && meters.isFinite()) { "Movements must be finite" }
    val radians = Math.toRadians(bearing)
    val (north, east) = degreeOffsets(meters * cos(radians), meters * sin(radians))
    return Coordinates((latitude + north).coerceIn(-90.0, 90.0), wrapLongitude(longitude + east))
}

fun Coordinates.rounded(): Coordinates = Coordinates(roundedDegrees(latitude), roundedDegrees(longitude))

private fun Coordinates.degreeOffsets(northMeters: Double, eastMeters: Double): Pair<Double, Double> {
    val phi = Math.toRadians(latitude)
    // WGS 84 length of one degree of latitude and longitude at this latitude.
    val latitudeDegree = 111_132.92 - 559.82 * cos(2 * phi) + 1.175 * cos(4 * phi) - 0.0023 * cos(6 * phi)
    val longitudeDegree = 111_412.84 * cos(phi) - 93.5 * cos(3 * phi) + 0.118 * cos(5 * phi)
    // East and west are undefined exactly at a pole.
    val east = if (longitudeDegree > 1e-6) (eastMeters / longitudeDegree) % 360.0 else 0.0
    return northMeters / latitudeDegree to east
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

private fun wrapLongitude(value: Double): Double {
    val remainder = value % 360.0
    return when {
        remainder > 180.0 -> remainder - 360.0
        remainder < -180.0 -> remainder + 360.0
        else -> remainder
    }
}

private fun wrapLongitude(value: BigDecimal): Double {
    val fullTurn = BigDecimal.valueOf(360)
    val remainder = value.remainder(fullTurn)
    return when {
        remainder > BigDecimal.valueOf(180) -> remainder.subtract(fullTurn)
        remainder < BigDecimal.valueOf(-180) -> remainder.add(fullTurn)
        else -> remainder
    }.toDouble()
}
