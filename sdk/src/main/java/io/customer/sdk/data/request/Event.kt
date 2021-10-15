package io.customer.sdk.data.request

internal data class Event(
    val name: String,
    val data: Map<String, Any>
)
