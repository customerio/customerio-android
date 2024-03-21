package io.customer.sdk.data.request

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import io.customer.sdk.data.model.CustomAttributes
import java.util.*

@JsonClass(generateAdapter = true)
data class Device(
    @field:Json(name = "id") val token: String,
    val platform: String = "android",
    @field:Json(name = "last_used") val lastUsed: Date?, // nullable to cater for `lastUsed` field in older versions of the SDK
    val attributes: CustomAttributes
)

@JsonClass(generateAdapter = true)
internal data class DeviceRequest(
    val device: Device
)
