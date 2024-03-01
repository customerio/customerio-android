package io.customer.sdk.data.request

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.util.*

@JsonClass(generateAdapter = false)
internal enum class DeliveryType {
    in_app
}

@JsonClass(generateAdapter = true)
internal data class DeliveryPayload(
    @field:Json(name = "delivery_id") val deliveryID: String,
    val event: MetricEvent,
    val timestamp: Date,
    @field:Json(name = "metadata") val metaData: Map<String, String>
)

@JsonClass(generateAdapter = true)
internal data class DeliveryEvent(
    val type: DeliveryType,
    val payload: DeliveryPayload
)
