package `in`.bipul.gpsemulator

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PinStoreTest {
    @Test
    fun savesAndRestoresPinsAndDeletesOnlyTheSelectedId() {
        val preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("pins_test", Context.MODE_PRIVATE)
        val store = PinStore(preferences)
        assertTrue(store.read().isEmpty())
        store.add(" Office ", Coordinates(12.5, -45.25))
        store.add("Office", Coordinates(0.0, 0.0))
        val restored = PinStore(preferences).read()
        assertEquals("Office", restored.first().name)
        assertEquals(Coordinates(12.5, -45.25), restored.first().coordinates)
        assertTrue(restored[0].id != restored[1].id)
        store.delete(restored.first().id)
        assertEquals(listOf(restored.last()), store.read())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyNames() {
        PinStore(RuntimeEnvironment.getApplication().getSharedPreferences("pins_test", Context.MODE_PRIVATE))
            .add("  ", Coordinates(0.0, 0.0))
    }

    @Test
    fun renamesOnlyTheChosenPinAndPreservesItsIdentityAndCoordinates() {
        val store = PinStore(RuntimeEnvironment.getApplication().getSharedPreferences("pins_test", Context.MODE_PRIVATE))
        store.add("Office", Coordinates(12.5, -45.25))
        store.add("Office", Coordinates(0.0, 0.0))
        val original = store.read()
        store.rename(original.first().id, " New office ")
        assertEquals(original.first().copy(name = "New office"), store.read().first())
        assertEquals(original.last(), store.read().last())
        assertThrows(IllegalArgumentException::class.java) { store.rename(original.first().id, " ") }
        assertThrows(IllegalArgumentException::class.java) { store.rename(original.first().id, "x".repeat(61)) }
        assertThrows(IllegalArgumentException::class.java) { store.rename("missing", "Office") }
        assertEquals("New office", store.read().first().name)
    }
}
