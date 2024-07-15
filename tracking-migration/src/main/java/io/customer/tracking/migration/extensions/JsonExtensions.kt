package io.customer.tracking.migration.extensions

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONArray.toList(): List<JSONObject> {
    return (0 until length()).mapNotNull(::optJSONObject)
}

internal fun JSONObject.jsonObjectOrNull(key: String): JSONObject? {
    return if (isNull(key)) null else optJSONObject(key)
}

internal fun JSONObject.stringOrNull(key: String): String? {
    return if (isNull(key)) null else optString(key)
}

internal fun JSONObject.longOrNull(key: String): Long? {
    return if (isNull(key)) null else optLong(key)
}

/**
 * Extension function to ensure that the required field is present in JSON object.
 * The function will throw an exception if the field is missing, null or cannot be parsed.
 * The function supports parsing of String, Long and JSONObject types directly.
 * For other types, the function will throw an exception if the type cannot be casted directly.
 */
internal inline fun <reified T : Any> JSONObject.requireField(key: String): T {
    val value: T? = when {
        isNull(key) -> null
        else -> when (T::class) {
            String::class -> optString(key) as? T
            Long::class -> optLong(key) as? T
            JSONObject::class -> optJSONObject(key) as? T
            else -> opt(key) as? T ?: throw IllegalArgumentException(
                "Type: ${T::class} is not supported by migration JSON parser for key: $key in $this. Could not parse task."
            )
        }
    }

    return requireNotNull(value) { "Required key '$key' is missing or null in $this. Could not parse task." }
}

/**
 * Similar to [requireField] but also removes the field from the JSON object.
 * This is useful when parsing the JSON object and removing the field after parsing.
 */
internal inline fun <reified T : Any> JSONObject.requireAndRemoveField(key: String): T {
    val value = requireField<T>(key)
    remove(key)
    return value
}
