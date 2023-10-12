package io.customer.sdk.data.request

import com.squareup.moshi.JsonClass
import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.data.model.EventType

@JsonClass(generateAdapter = true)
internal data class Event(
    val name: String,
    val type: EventType,
    val data: CustomAttributes,
    val timestamp: Long? = null
)
