package io.customer.messaginginapp.gist.data.model.response

import io.customer.messaginginapp.gist.data.model.InboxMessage

/**
 * Maps the API response model to domain model. This allows us to have safe defaults
 * for missing or nullable API fields so the domain model can remain predictable
 * and free of nullability checks elsewhere.
 *
 * Returns null if required fields (queueId, sentAt) are missing, indicating an invalid message
 * that should be filtered out.
 */
internal fun InboxMessageResponse.toDomain(): InboxMessage? {
    // Skip invalid messages missing required fields
    if (queueId == null || sentAt == null) {
        return null
    }

    return InboxMessage(
        queueId = queueId,
        deliveryId = deliveryId,
        expiry = expiry,
        sentAt = sentAt,
        topics = topics ?: emptyList(),
        type = type ?: "",
        opened = opened ?: false,
        priority = priority,
        properties = properties ?: emptyMap()
    )
}

// Formats inbox message for logging.
internal fun InboxMessage.toLogString(): String = "$queueId (deliveryId: $deliveryId)"
