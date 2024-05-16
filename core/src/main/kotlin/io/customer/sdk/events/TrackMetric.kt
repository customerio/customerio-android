package io.customer.sdk.events

import java.util.Locale

/**
 * Event wrapper for tracking metrics like push, in-app, etc. with
 * additional properties like deliveryId, deviceToken, metadata, etc.
 */
sealed interface TrackMetric {
    val metric: Metric
    val deliveryId: String

    /**
     * Event class for tracking push metrics
     *
     * @param metric Metric event to be tracked
     * @param deliveryId Delivery ID of push notification
     * @param deviceToken Device token to which the push notification was sent
     */
    data class Push(
        override val metric: Metric,
        override val deliveryId: String,
        val deviceToken: String
    ) : TrackMetric

    /**
     * Event class for tracking in-app metrics
     *
     * @property metric Metric event to be tracked
     * @property deliveryId Delivery ID of in-app message
     * @property metadata Additional metadata for the in-app message
     */
    data class InApp @JvmOverloads constructor(
        override val metric: Metric,
        override val deliveryId: String,
        val metadata: Map<String, String> = emptyMap()
    ) : TrackMetric
}

/**
 * Extension function to convert [TrackMetric] to a map for tracking metrics.
 * Using map for tracking metrics allows easy serialization and modification
 * in final JSON without implementing custom serialization.
 */
fun TrackMetric.asMap(): Map<String, Any> {
    val result = mutableMapOf<String, Any>()
    when (this) {
        is TrackMetric.Push -> {
            result["recipient"] = deviceToken
        }

        is TrackMetric.InApp -> {
            result.putAll(metadata)
        }
    }
    result["metric"] = metric.name.lowercase(Locale.ENGLISH)
    result["deliveryId"] = deliveryId
    return result
}
