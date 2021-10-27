package io.customer.sdk.data.request

import com.squareup.moshi.Json

internal data class Device(
    @field:Json(name = "id") val token: String,
    val platform: String = "android",
    val lastUsed: Long
)

internal data class DeviceRequest(
    val device: Device
)
