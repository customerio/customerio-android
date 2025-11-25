package io.customer.sdk.insights

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive

/**
 * Internal data class for building diagnostic events.
 */
@PublishedApi
internal data class DiagnosticBuilder(
    val name: String,
    val metadataBuilder: (JsonObjectBuilder.() -> Unit)?
)

/**
 * Builder scope for constructing diagnostic events with a fluent API.
 * Used internally by Diagnostics.record() to provide a clean builder pattern.
 */
class DiagnosticBuilderScope(
    private val name: String
) {
    private var metadataBuilder: (JsonObjectBuilder.() -> Unit)? = null

    /**
     * Add metadata to the diagnostic event using a Map builder.
     * This is the recommended API for most use cases - clean and simple.
     *
     * Example:
     * ```
     * Diagnostics.record(EVENT) {
     *     metadata {
     *         put("delivery_id", deliveryId)
     *         put("device_token", deviceToken)
     *         put("count", 42)
     *         put("enabled", true)
     *     }
     * }
     * ```
     */
    fun withData(action: MutableMap<String, Any?>.() -> Unit) {
        withJson {
            val data = buildMap(builderAction = action)
            data.forEach { (key, value) ->
                when (value) {
                    null -> put(key, JsonNull)
                    is Number -> put(key, JsonPrimitive(value))
                    is Boolean -> put(key, JsonPrimitive(value))
                    is String -> put(key, JsonPrimitive(value))
                    else -> put(key, JsonPrimitive(value.toString()))
                }
            }
        }
    }

    /**
     * Add metadata to the diagnostic event using JsonObjectBuilder DSL.
     * Use this only for complex nested structures where you need full JsonObject control.
     * For simple key-value pairs, prefer metadata() instead.
     *
     * Example:
     * ```
     * Diagnostics.record(EVENT) {
     *     metadataJson {
     *         put("delivery_id", deliveryId)  // Uses extension functions
     *         put("nested", buildJsonObject {
     *             put("key", "value")
     *         })
     *     }
     * }
     * ```
     */
    fun withJson(action: JsonObjectBuilder.() -> Unit) {
        metadataBuilder = action
    }

    @PublishedApi
    internal fun build(): DiagnosticBuilder = DiagnosticBuilder(
        name = name,
        metadataBuilder = metadataBuilder
    )
}
