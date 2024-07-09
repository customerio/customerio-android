package io.customer.sdk.data.request
import io.customer.sdk.data.model.CustomAttributes
import java.util.*

data class Device(
//    @field:Json(name = "id") val token: String,
    val platform: String = "android",
//    @field:Json(name = "last_used") val lastUsed: Date?, // nullable to cater for `lastUsed` field in older versions of the SDK
    val attributes: CustomAttributes
)

internal data class DeviceRequest(
    val device: Device
)
