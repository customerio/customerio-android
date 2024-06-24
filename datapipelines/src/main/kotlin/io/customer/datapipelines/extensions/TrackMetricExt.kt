package io.customer.datapipelines.extensions

import io.customer.sdk.events.TrackMetric
import io.customer.sdk.events.serializedName

/**
 * Extension function to convert [TrackMetric] to a map for tracking metrics.
 * Using map for tracking metrics allows easy serialization and modification
 * in final JSON without implementing custom serialization.
 */
internal fun TrackMetric.asMap(): Map<String, Any> {
    val result = mutableMapOf<String, Any>()
    when (this) {
        is TrackMetric.Push -> {
            result["recipient"] = deviceToken
        }

        is TrackMetric.InApp -> {
            result.putAll(metadata)
        }
    }
    result["metric"] = metric.serializedName
    result["deliveryId"] = deliveryId
    return result
}

/**
 * Extension function to identify the type of [TrackMetric].
 * It is mainly used for logging purposes.
 */
internal val TrackMetric.type: String
    get() = when (this) {
        is TrackMetric.Push -> "push"
        is TrackMetric.InApp -> "in-app"
    }
