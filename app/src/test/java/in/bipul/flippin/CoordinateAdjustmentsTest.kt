package `in`.bipul.flippin

import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateAdjustmentsTest {
    @Test
    fun nudgesAtPolesAndDateLineStayWithinValidCoordinates() {
        val nudged = Coordinates(90.0, 179.99999).nudged(0.00001, 0.00002)
        assertEquals(90.0, nudged.latitude, 1e-10)
        assertEquals(-179.99999, nudged.longitude, 1e-10)
    }

    @Test
    fun repeatedDecimalNudgesDoNotFillTheFieldsWithFloatingPointArtifacts() {
        assertEquals(Coordinates(0.3, 0.3), Coordinates(0.1, 0.2).nudged(0.2, 0.1))
        var point = Coordinates(12.5, 179.99999)
        repeat(100) { point = point.nudged(0.00001, 0.00001) }
        assertEquals(Coordinates(12.501, -179.99901), point)
    }
}
