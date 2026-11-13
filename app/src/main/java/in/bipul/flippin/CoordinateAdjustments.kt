package `in`.bipul.flippin

import java.math.BigDecimal

enum class PinScale(val step: Double) {
    WORLD(1.0),
    AREA(0.001),
    FINE(0.00001)
}

fun Coordinates.nudged(north: Double, east: Double): Coordinates {
    require(north.isFinite() && east.isFinite()) { "Adjustments must be finite" }
    return Coordinates(
        BigDecimal.valueOf(latitude).add(BigDecimal.valueOf(north)).toDouble().coerceIn(-90.0, 90.0),
        wrapLongitude(BigDecimal.valueOf(longitude).add(BigDecimal.valueOf(east)))
    )
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
