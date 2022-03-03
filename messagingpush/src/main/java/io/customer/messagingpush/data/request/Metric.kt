package io.customer.messagingpush.data.request

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.util.*

@JsonClass(generateAdapter = false)
enum class MetricEvent {
    delivered, opened, converted;
}

@JsonClass(generateAdapter = true)
internal data class Metric(
    @field:Json(name = "delivery_id") val deliveryID: String,
    @field:Json(name = "device_id") val deviceToken: String,
    val event: MetricEvent,
    val timestamp: Date
)
