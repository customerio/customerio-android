package io.customer.sdk.events

import java.util.Locale

enum class Metric {
    Delivered,
    Opened,
    Converted,
    Clicked
}

sealed interface TrackMetric {
    val metric: Metric
    val deliveryId: String

    data class Push(
        override val metric: Metric,
        override val deliveryId: String,
        val deviceToken: String
    ) : TrackMetric

    data class InApp(
        override val metric: Metric,
        override val deliveryId: String,
        val metadata: Map<String, String>
    ) : TrackMetric
}

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
