package io.customer.datapipelines.plugins

import com.segment.analytics.kotlin.core.BaseEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Gets the value at nested path in the JSON object.
 * Example: `{"a": {"b": "c"}}.findAtPath("a.b")` returns c
 */
internal fun JsonObject.findAtPath(path: String): JsonPrimitive? = kotlin.runCatching {
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
}.getOrNull()

fun BaseEvent.findInContextAtPath(
    path: String
): JsonPrimitive? = context.findAtPath(path = path)
