package io.customer.datapipelines.plugins

import com.segment.analytics.kotlin.core.BaseEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Gets the value(s) at nested path in the JSON object.
 * Handles arrays by returning all matching elements.
 * Example1: `{"a": [{"b": "c"}, {"e": "f"}]}.findAtPath("a.b")` returns [JsonPrimitive("c")]
 * Example2: `{"a": {"b": "c"}}.findAtPath("a.b")` returns [JsonPrimitive("c")]
 */
fun JsonObject.findAtPath(path: String): List<JsonPrimitive> {
    // Split the path into individual keys
    val keys = path.split('.')
    // Start the recursive search
    return findAtPathRecursive(this, keys)
}

/**
 * Recursively searches for values at the specified path in the JSON element.
 * @param element The current JSON element being processed.
 * @param keys The remaining path keys to process.
 * @return A list of JsonPrimitives found at the path.
 */
private fun findAtPathRecursive(element: JsonElement, keys: List<String>): List<JsonPrimitive> {
    // Base case: If no more keys, check if the element is a JsonPrimitive
    if (keys.isEmpty()) {
        return if (element is JsonPrimitive) listOf(element) else emptyList()
    }

    // Extract the first key and the remaining keys
    val key = keys.first()
    val remainingKeys = keys.drop(1)

    return when (element) {
        is JsonObject -> {
            // If element is a JsonObject, look up the next key
            val nextElement = element[key]
            // If the key exists, continue the recursive search
            if (nextElement != null) findAtPathRecursive(nextElement, remainingKeys) else emptyList()
        }
        is JsonArray -> {
            // If element is a JsonArray, apply the search to each element
            element.flatMap { findAtPathRecursive(it, keys) }
        }
        else -> {
            // If element is neither JsonObject nor JsonArray, return an empty list
            emptyList()
        }
    }
}

/**
 * Extension function for BaseEvent to search in its context at a specified path.
 * @param path The dot-separated path to search for.
 * @return A list of JsonPrimitives found at the path.
 */
fun BaseEvent.findInContextAtPath(path: String): List<JsonPrimitive> =
    context.findAtPath(path)
