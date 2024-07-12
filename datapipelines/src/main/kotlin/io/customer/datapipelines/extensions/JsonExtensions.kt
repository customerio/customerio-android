package io.customer.datapipelines.extensions

import com.segment.analytics.kotlin.core.utilities.toJsonElement
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
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = get(key)
        put(key, value.toSerializableJson())
    }
}

/**
 * Transforms [JSONArray] to [JsonArray] by leveraging kotlinx.serialization.json.
 */
fun JSONArray.toJsonArray(): JsonArray = buildJsonArray {
    for (i in 0 until length()) {
        val value = get(i)
        add(value.toSerializableJson())
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
