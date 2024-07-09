package io.customer.sdk.data.request

import io.customer.sdk.data.model.CustomAttributes

internal data class Event(
    val name: String,
    val type: String,
    val data: CustomAttributes,
    val timestamp: Long? = null
)
