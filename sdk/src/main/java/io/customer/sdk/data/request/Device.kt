package io.customer.sdk.data.request

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
internal data class Device(
    @field:Json(name = "id") val token: String,
    val platform: String = "android",
    val lastUsed: Long,
    val attributes: Map<String, Any>
)

@JsonClass(generateAdapter = true)
internal data class DeviceRequest(
    val device: Device
)
