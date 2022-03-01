package io.customer.sdk.data.request

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Device(
    @field:Json(name = "id") val token: String,
    val platform: String = "android",
    val lastUsed: Long
)

@JsonClass(generateAdapter = true)
data class DeviceRequest(
    val device: Device
)
