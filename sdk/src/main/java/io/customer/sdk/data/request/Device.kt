package io.customer.sdk.data.request

import com.squareup.moshi.Json

internal data class Device(
    @Json(name = "id") val token: String,
    val platform: String = "android",
    val lastUsed: Long
)
