@file:UseContextualSerialization(Any::class)

package io.customer.sdk.data.request

import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.data.model.EventType
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization

@Serializable
internal data class Event(
    val name: String,
    val type: EventType,
    val data: CustomAttributes,
    val timestamp: Long? = null
)
