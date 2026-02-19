package io.customer.messaginginapp.gist.data.model.response

import io.customer.base.internal.InternalCustomerIOApi
import io.customer.messaginginapp.gist.data.model.InboxMessage
import java.util.Date

/**
 * Factory for creating InboxMessage domain models from various sources (API responses, Maps).
 */
@InternalCustomerIOApi
object InboxMessageFactory {
    /**
     * Converts InboxMessageResponse to InboxMessage with safe defaults for nullable fields.
     * Returns null if required fields (queueId, sentAt) are missing.
     */
    internal fun fromResponse(response: InboxMessageResponse): InboxMessage? {
        // Skip invalid messages missing required fields
        if (response.queueId == null || response.sentAt == null) {
            return null
        }

        return InboxMessage(
            queueId = response.queueId,
            deliveryId = response.deliveryId,
            expiry = response.expiry,
            sentAt = response.sentAt,
            topics = response.topics ?: emptyList(),
            type = response.type ?: "",
            opened = response.opened ?: false,
            priority = response.priority,
            properties = response.properties ?: emptyMap()
        )
    }

    /**
     * Converts Map to InboxMessage for SDK wrapper integrations.
     * Returns null if required fields (queueId, sentAt) are missing or invalid.
     */
    fun fromMap(map: Map<String, Any?>): InboxMessage? {
        @Suppress("UNCHECKED_CAST")
        val properties = map["properties"] as? Map<String, Any?> ?: emptyMap()
        return fromResponse(
            InboxMessageResponse(
                queueId = map["queueId"] as? String,
                deliveryId = map["deliveryId"] as? String,
                expiry = (map["expiry"] as? Number)?.toLong()?.let { Date(it) },
                sentAt = (map["sentAt"] as? Number)?.toLong()?.let { Date(it) },
                topics = (map["topics"] as? List<*>)?.mapNotNull { it as? String },
                type = map["type"] as? String,
                opened = map["opened"] as? Boolean,
                priority = (map["priority"] as? Number)?.toInt(),
                properties = properties
            )
        )
    }
}
