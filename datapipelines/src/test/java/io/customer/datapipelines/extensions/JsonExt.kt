package io.customer.datapipelines.extensions

import io.customer.sdk.data.model.CustomAttributes
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.amshove.kluent.internal.assertEquals

/**
 * Similar to Kluent's `shouldBeEqualTo` but for comparing JSON objects with custom attributes map.
 */
infix fun JsonObject.shouldMatchTo(expected: CustomAttributes): JsonObject {
    val mapped = JsonObject(
        with(Json) { expected.mapValues { (_, value) -> encode(value) } }
    )
    return this.apply { assertEquals(mapped, this) }
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
