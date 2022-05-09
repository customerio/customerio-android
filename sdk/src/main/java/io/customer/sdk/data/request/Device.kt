package io.customer.sdk.data.request

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import io.customer.sdk.data.model.CustomAttributes
import java.util.*

@JsonClass(generateAdapter = true)
data class Device(
    @field:Json(name = "id") val token: String,
    val platform: String = "android",
    val lastUsed: Date,
    val attributes: CustomAttributes
)

@JsonClass(generateAdapter = true)
internal data class DeviceRequest(
    val device: Device
)
