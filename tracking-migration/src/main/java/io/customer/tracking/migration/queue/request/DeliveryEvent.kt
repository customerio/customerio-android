package io.customer.sdk.data.request

import java.util.*

internal enum class DeliveryType {
    in_app
}

internal data class DeliveryPayload(
//    @field:Json(name = "delivery_id") val deliveryID: String,
    val event: MetricEvent,
    val timestamp: Date
//    @field:Json(name = "metadata") val metaData: Map<String, String>
)

internal data class DeliveryEvent(
    val type: DeliveryType,
    val payload: DeliveryPayload
)
