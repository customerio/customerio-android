@file:UseContextualSerialization(Date::class)

package io.customer.sdk.data.request

import java.util.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization

@Serializable
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

@Serializable
internal data class Metric(
    @SerialName("delivery_id") val deliveryID: String,
    @SerialName("device_id") val deviceToken: String,
    val event: MetricEvent,
    val timestamp: Date
)
