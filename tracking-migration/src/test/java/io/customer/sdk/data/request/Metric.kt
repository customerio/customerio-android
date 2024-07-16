package io.customer.sdk.data.request

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.util.Date

@JsonClass(generateAdapter = false)
enum class MetricEvent {
    delivered, opened, converted, clicked;

    companion object {
        fun getEvent(event: String?): MetricEvent? {
            return if (event.isNullOrBlank()) {
                null
            } else {
                values().find { value -> value.name.equals(event, ignoreCase = true) }
            }
        }
    }
}

@JsonClass(generateAdapter = true)
internal data class Metric(
    @field:Json(name = "delivery_id") val deliveryID: String,
    @field:Json(name = "device_id") val deviceToken: String,
    val event: MetricEvent,
    val timestamp: Date
)
