package io.customer.sdk.data.request

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
internal data class Event(
    val name: String,
    val data: Map<String, Any>
)
