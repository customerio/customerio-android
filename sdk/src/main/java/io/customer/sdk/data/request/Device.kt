@file:UseContextualSerialization(Any::class, Date::class)

package io.customer.sdk.data.request

import io.customer.sdk.data.model.CustomAttributes
import java.util.*
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization

@Serializable
data class Device(
    @SerialName("id") val token: String,
    val platform: String = "android",
    val lastUsed: Date,
    @Contextual val attributes: CustomAttributes
)

@Serializable
internal data class DeviceRequest(
    val device: Device
)
