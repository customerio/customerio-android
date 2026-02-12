package io.customer.messaginginapp.gist.data.model

import java.util.Date

/**
 * Represents an inbox message for a user.
 *
 * Inbox messages are persistent messages that can be displayed in a message center or inbox UI.
 * They support read/unread states, expiration, and custom properties.
 *
 * @property queueId Internal queue identifier (for SDK use)
 * @property deliveryId Unique identifier for this message delivery
 * @property expiry Optional expiration date. Messages may be hidden after this date.
 * @property sentAt Optional date when the message was sent
 * @property topics List of topic identifiers associated with this message. Empty list if no topics.
 * @property type Message type identifier
 * @property opened Whether the user has opened/read this message
 * @property priority Optional priority for message ordering. Lower values = higher priority (e.g., 1 is higher priority than 100)
 * @property properties Custom key-value properties associated with this message
 */
data class InboxMessage(
    val queueId: String,
    val deliveryId: String?,
    val expiry: Date?,
    val sentAt: Date,
    val topics: List<String>,
    val type: String,
    val opened: Boolean,
    val priority: Int?,
    val properties: Map<String, Any?>
)
