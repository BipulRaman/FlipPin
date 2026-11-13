package `in`.bipul.flippin

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedPin(val id: String, val name: String, val coordinates: Coordinates)

class PinStore(private val preferences: SharedPreferences) {
    fun read(): List<SavedPin> {
        val array = JSONArray(preferences.getString(KEY, "[]"))
        return (0 until array.length()).map { index ->
            val pin = array.getJSONObject(index)
            SavedPin(
                pin.getString("id"), pin.getString("name"),
                Coordinates(pin.getDouble("latitude"), pin.getDouble("longitude"))
            )
        }
    }

    fun add(name: String, coordinates: Coordinates) {
        val trimmed = name.trim()
        require(trimmed.length in 1..60) { "Pin name must contain 1 to 60 characters" }
        write(read() + SavedPin(UUID.randomUUID().toString(), trimmed, coordinates))
    }

    fun delete(id: String) = write(read().filterNot { it.id == id })

    private fun write(pins: List<SavedPin>) {
        val array = JSONArray()
        pins.forEach { pin ->
            array.put(JSONObject().apply {
                put("id", pin.id)
                put("name", pin.name)
                put("latitude", pin.coordinates.latitude)
                put("longitude", pin.coordinates.longitude)
            })
        }
        preferences.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        const val KEY = "pins"
    }
}
