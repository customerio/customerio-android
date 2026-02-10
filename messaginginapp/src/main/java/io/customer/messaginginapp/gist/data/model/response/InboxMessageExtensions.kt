package io.customer.messaginginapp.gist.data.model.response

import io.customer.messaginginapp.gist.data.model.InboxMessage
import java.util.Date

/**
 * Maps the API response model to domain model. This allows us to have safe defaults
 * for missing or nullable API fields so the domain model can remain predictable
 * and free of nullability checks elsewhere.
 */
internal fun InboxMessageResponse.toDomain(): InboxMessage = InboxMessage(
    // queueId should always be present; fallback is defensive only
    queueId = queueId ?: "invalid_queue_id",
    deliveryId = deliveryId,
    expiry = expiry,
    // sentAt should always be present; fallback is defensive only
    sentAt = sentAt ?: Date(),
    topics = topics ?: emptyList(),
    type = type ?: "",
    opened = opened ?: false,
    priority = priority,
    properties = properties ?: emptyMap()
)

// Formats inbox message for logging.
internal fun InboxMessage.toLogString(): String = "$queueId (deliveryId: $deliveryId)"
