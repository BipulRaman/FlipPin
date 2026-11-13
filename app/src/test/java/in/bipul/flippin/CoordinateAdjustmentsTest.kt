package `in`.bipul.flippin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class CoordinateAdjustmentsTest {
    @Test
    fun nudgesAtPolesAndDateLineStayWithinValidCoordinates() {
        val nudged = Coordinates(90.0, 179.99999).nudged(0.00001, 0.00002)
        assertEquals(90.0, nudged.latitude, 1e-10)
        assertEquals(-179.99999, nudged.longitude, 1e-10)
    }

    @Test
    fun movementsUseTrueMetresAtTheCurrentLatitude() {
        // WGS 84 reference: 1° of latitude is 110,574 m at the equator; 1° of longitude is 55,800 m at 60°.
        val north = Coordinates(0.0, 10.0).moved(1000.0, 0.0)
        assertEquals(1000 / 110_574.0, north.latitude, 1e-6)
        assertEquals(10.0, north.longitude, 0.0)
        val east = Coordinates(60.0, 10.0).moved(0.0, 1000.0)
        assertEquals(60.0, east.latitude, 0.0)
        assertEquals(10.0 + 1000 / 55_800.0, east.longitude, 1e-6)
    }

    @Test
    fun movementsStayValidAtPolesAndTheDateLineAndKeepFieldsShort() {
        assertEquals(Coordinates(90.0, 45.0), Coordinates(90.0, 45.0).moved(5.0, 50.0))
        val wrapped = Coordinates(0.0, 179.9999).moved(0.0, 50.0)
        assertEquals(-179.99965, wrapped.longitude, 1e-5)
        var walk = Coordinates(20.0, 30.0)
        repeat(20) { walk = walk.moved(5.0, 0.0) }
        assertTrue(walk.latitude.toString().substringAfter('.').length <= 7)
        assertEquals(30.0, walk.longitude, 0.0)
    }

    @Test
    fun repeatedDecimalNudgesDoNotFillTheFieldsWithFloatingPointArtifacts() {
        assertEquals(Coordinates(0.3, 0.3), Coordinates(0.1, 0.2).nudged(0.2, 0.1))
        var point = Coordinates(12.5, 179.99999)
        repeat(100) { point = point.nudged(0.00001, 0.00001) }
        assertEquals(Coordinates(12.501, -179.99901), point)
    }

    @Test
    fun travelFollowsAnyBearingInTrueMetresWithoutRoundingAwaySmallSteps() {
        val east = Coordinates(60.0, 10.0).travelled(90.0, 1000.0)
        assertEquals(60.0, east.latitude, 1e-12)
        assertEquals(10.0 + 1000 / 55_800.0, east.longitude, 1e-6)
        // South-west splits the distance equally; 1° is 110,574 m of latitude and 111,319 m of longitude here.
        val southWest = Coordinates(0.0, 0.0).travelled(225.0, 1000.0)
        assertEquals(-1000 * sqrt(0.5) / 110_574.27, southWest.latitude, 1e-9)
        assertEquals(-1000 * sqrt(0.5) / 111_319.46, southWest.longitude, 1e-9)
        // A 4 mm joystick frame survives; seven-decimal rounding is only for display.
        val crawl = Coordinates(0.0, 0.0).travelled(0.0, 0.004)
        assertTrue(crawl.latitude > 0.0)
        assertEquals(Coordinates(0.0, 0.0), crawl.rounded())
        assertTrue(Coordinates(0.0, 179.9999).travelled(90.0, 50.0).longitude < -179.9996)
        assertEquals(90.0, Coordinates(89.99999, 0.0).travelled(0.0, 10.0).latitude, 0.0)
    }

}
