package io.customer.datapipelines.extensions

import com.segment.analytics.kotlin.core.emptyJsonObject
import io.customer.sdk.data.model.CustomAttributes
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.amshove.kluent.internal.assertEquals

/**
 * Similar to Kluent's `shouldBeEqualTo` but for comparing JSON objects with custom attributes map.
 */
infix fun JsonObject.shouldMatchTo(expected: CustomAttributes): JsonObject {
    return this.apply { assertEquals(expected.toJsonObject(), this) }
}

/**
 * Converts a map of custom attributes to a JSON object.
 * If the map is empty, it returns an empty JSON object.
 */
fun CustomAttributes?.toJsonObject(): JsonObject {
    val encodedMap = if (this.isNullOrEmpty()) {
        emptyMap()
    } else {
        with(Json) {
            mapValues { (_, value) -> encode(value) }
        }
    }
    return JsonObject(encodedMap)
}

/**
 * Decodes a JSON string to a JSON object.
 * If the string is null, it returns an empty JSON object.
 */
fun String?.decodeJson(): JsonObject = this?.let { json ->
    Json.decodeFromString(json)
} ?: emptyJsonObject

/**
 * Encodes a serializable object to a JSON element.
 */
inline fun <reified T> T.encodeToJsonElement(): JsonElement {
    return Json.encodeToJsonElement(this)
}

@OptIn(ExperimentalSerializationApi::class)
private fun <T> Json.encode(value: T?): JsonElement = when (value) {
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)

    is List<*> -> buildJsonArray {
        value.forEach { add(encode(it)) }
    }

    is Map<*, *> -> buildJsonObject {
        value.forEach { (key, v) ->
            put(key.toString(), encode(v))
        }
    }

    null -> JsonPrimitive(null)
    else -> throw IllegalArgumentException("Unsupported type: $value")
}

internal val JsonObject.deviceToken: String?
    get() = this.getStringAtPath("device.token")

/**
 * Get the value at the nested path in the JSON object.
 * Example: `{"a": {"b": "c"}}.getNestedString("a.b")` returns c
 */
fun JsonObject.getJsonPrimitiveAtPath(path: String): JsonPrimitive? {
    // Split the path into keys
    val keys = path.split(".")
    // Start with the current JSON object
    var currentElement = this
    // Traverse the JSON object to find the value at the path
    for (key in keys.dropLast(1)) {
        // If the key does not exist, return null
        currentElement = currentElement[key]?.jsonObject ?: return null
    }
    // Return the value at the last key in the path
    return currentElement[keys.last()]?.jsonPrimitive
}

fun JsonObject.getStringAtPath(path: String): String? {
    return getJsonPrimitiveAtPath(path)?.content
}

fun JsonObject.getJsonObjectAtPath(path: String): JsonObject? {
    return getJsonPrimitiveAtPath(path)?.jsonObject
}
