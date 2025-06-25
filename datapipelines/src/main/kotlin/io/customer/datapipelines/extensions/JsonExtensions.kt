package io.customer.datapipelines.extensions

import com.segment.analytics.kotlin.core.utilities.toJsonElement
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.json.JSONArray
import org.json.JSONObject

/**
 * Transforms [JSONObject] to [JsonObject] by leveraging kotlinx.serialization.json.
 * This is useful when we receive JSONObject for migration tasks and we want to convert
 * it to JsonObject for processing.
 */
fun JSONObject.toJsonObject(): JsonObject = buildJsonObject {
    for (key in this@toJsonObject.keys()) {
        put(key, opt(key).toSerializableJson())
    }
}

/**
 * Transforms [JSONArray] to [JsonArray] by leveraging kotlinx.serialization.json.
 */
fun JSONArray.toJsonArray(): JsonArray = buildJsonArray {
    val range = 0 until length()
    for (index in range) {
        add(opt(index).toSerializableJson())
    }
}

/**
 * Transforms any object to [JsonElement] by leveraging kotlinx.serialization.json.
 * The method is recursive and can handle nested JSONObjects and JSONArrays.
 */
private fun Any?.toSerializableJson(): JsonElement {
    return when (this) {
        null -> JsonNull
        is JSONObject -> this.toJsonObject()
        is JSONArray -> this.toJsonArray()
        else -> this.toJsonElement()
    }
}

/**
 * Recursively sanitizes data for JSON serialization by:
 * 1. Replacing all `null` values in nested structure with [JsonNull]
 * 2. Removing entries with NaN or infinity values from maps
 * This ensures the data can be safely converted to JSON.
 *
 * @param logger Logger to log when invalid numeric values are removed
 */
internal fun Map<String, Any?>.sanitizeForJson(logger: Logger = SDKComponent.logger): Map<String, Any?> {
    val resultMap = mutableMapOf<String, Any>()

    for (entry in this.entries) {
        val sanitizedValue = entry.value.sanitizeValue(logger)
        if (sanitizedValue != null) {
            resultMap[entry.key] = sanitizedValue
        } else {
            logger.error("Removed invalid JSON numeric value (NaN or infinity) for key: ${entry.key}")
        }
    }

    return resultMap
}

@Suppress("UNCHECKED_CAST")
private fun <T> T?.sanitizeValue(logger: Logger = SDKComponent.logger): T? = when (this) {
    null -> JsonNull as T
    is Double, is Float -> if (isInvalidJsonNumber(this)) null else this
    is Map<*, *> -> (this as? Map<String, Any?>)?.sanitizeForJson(logger) as T
    is List<*> -> sanitizeList(logger) as T
    else -> this
}

private fun List<*>.sanitizeList(logger: Logger): List<*> {
    return this.mapNotNull {
        val sanitizedValue = it.sanitizeValue(logger)
        if (sanitizedValue == null) {
            logger.error("Removed invalid JSON numeric value (NaN or infinity)")
        }
        sanitizedValue
    }
}

private fun isInvalidJsonNumber(value: Any?): Boolean {
    return when (value) {
        is Float -> value.isNaN() || value.isInfinite()
        is Double -> value.isNaN() || value.isInfinite()
        else -> false
    }
}
