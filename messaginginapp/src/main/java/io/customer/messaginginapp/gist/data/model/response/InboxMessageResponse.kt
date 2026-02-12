package io.customer.messaginginapp.gist.data.model.response

import com.google.gson.annotations.JsonAdapter
import io.customer.messaginginapp.gist.data.model.adapters.Iso8601DateAdapter
import java.util.Date

/**
 * API response model matching backend JSON contract.
 * All fields nullable to allow for safe parsing.
 */
internal data class InboxMessageResponse(
    val queueId: String? = null,
    val deliveryId: String? = null,
    @JsonAdapter(Iso8601DateAdapter::class)
    val expiry: Date? = null,
    @JsonAdapter(Iso8601DateAdapter::class)
    val sentAt: Date? = null,
    val topics: List<String>? = null,
    val type: String? = null,
    val opened: Boolean? = null,
    val priority: Int? = null,
    val properties: Map<String, Any?>? = null
)
