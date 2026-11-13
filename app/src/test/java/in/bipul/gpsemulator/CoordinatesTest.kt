package `in`.bipul.gpsemulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class CoordinatesTest {
    @Test
    fun acceptsBoundariesAndSignedCoordinates() {
        assertEquals(Coordinates(-90.0, 180.0), Coordinates.parse("-90", "180"))
        assertEquals(Coordinates(90.0, -180.0), Coordinates.parse("90", "-180"))
        assertEquals(Coordinates(0.0, 0.0), Coordinates.parse("0", "0"))
        assertEquals(Coordinates(-12.5, 45.25), Coordinates.parse(" -12,5 ", "45.25"))
    }

    @Test
    fun rejectsOutOfRangeMissingAndNonFiniteInput() {
        for (value in listOf("", "NaN", "Infinity", "-Infinity", "1e309", "text", "90.000001", "-90.01")) {
            assertNull(Coordinates.parse(value, "0"))
        }
        for (value in listOf("", "NaN", "Infinity", "-Infinity", "180.01", "-180.01")) {
            assertNull(Coordinates.parse("0", value))
        }
    }

    @Test
    fun displayUsesDecimalDegreesRegardlessOfDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("-12.500000, 45.250000", Coordinates(-12.5, 45.25).display())
        } finally {
            Locale.setDefault(previous)
        }
    }
}
