package io.customer.tracking.migration.request
import io.customer.sdk.data.model.CustomAttributes
import java.util.*

data class Device(
//    @field:Json(name = "id") val token: String, // TODO: Add this field without moshi
    val platform: String = "android",
    // TODO: Add this field without moshi
//    @field:Json(name = "last_used") val lastUsed: Date?, // nullable to cater for `lastUsed` field in older versions of the SDK
    val attributes: CustomAttributes
)

internal data class DeviceRequest(
    val device: Device
)
