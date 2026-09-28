package life.airen.haminn.util

import org.json.JSONArray
import org.json.JSONObject

fun JSONArray.toList(): List<Any?> = (0 until length()).map { index ->
    when (val value = opt(index)) {
        is JSONObject -> value.toMap()
        is JSONArray -> value.toList()
        JSONObject.NULL -> null
        else -> value
    }
}

fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { key ->
    when (val value = opt(key)) {
        is JSONObject -> value.toMap()
        is JSONArray -> value.toList()
        JSONObject.NULL -> null
        else -> value
    }
}

fun JSONObject.requireString(name: String, maxLength: Int = 4096): String {
    val value = optString(name, "")
    require(value.isNotBlank() && value.length <= maxLength) { "Invalid $name" }
    return value
}
