package io.customer.sdk.data.request

import com.squareup.moshi.Json

enum class MetricEvent {
    delivered, opened, converted;
}

data class Metric(
    @field:Json(name = "delivery_id") val deliveryID: String,
    @field:Json(name = "device_id") val deviceToken: String,
    val event: MetricEvent,
    val timestamp: Long
)
