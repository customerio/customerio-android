@file:UseContextualSerialization(Date::class)

package io.customer.sdk.data.request

import java.util.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization

@Serializable
internal enum class DeliveryType {
    in_app
}

@Serializable
internal data class DeliveryPayload(
    @SerialName("delivery_id") val deliveryID: String,
    val event: MetricEvent,
    val timestamp: Date,
    @SerialName("metadata") val metaData: Map<String, String>
)

@Serializable
internal data class DeliveryEvent(
    val type: DeliveryType,
    val payload: DeliveryPayload
)
