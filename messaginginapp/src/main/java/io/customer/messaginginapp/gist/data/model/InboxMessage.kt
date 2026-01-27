package io.customer.messaginginapp.gist.data.model

import com.google.gson.annotations.JsonAdapter
import io.customer.messaginginapp.gist.data.model.adapters.Iso8601DateAdapter
import java.util.Date

/**
 * Represents an inbox message for a user.
 *
 * Inbox messages are persistent messages that can be displayed in a message center or inbox UI.
 * They support read/unread states, expiration, and custom properties.
 *
 * @property deliveryId Unique identifier for this message delivery
 * @property expiry Optional expiration date. Messages may be hidden after this date.
 * @property sentAt Optional date when the message was sent
 * @property topics List of topic identifiers associated with this message
 * @property type Message type identifier
 * @property opened Whether the user has opened/read this message
 * @property priority Priority for message ordering. Lower values = higher priority (e.g., 1 is higher priority than 100)
 * @property properties Optional custom key-value properties associated with this message
 * @property queueId Internal queue identifier (for SDK use)
 */
data class InboxMessage(
    val deliveryId: String = "",
    @JsonAdapter(Iso8601DateAdapter::class)
    val expiry: Date? = null,
    @JsonAdapter(Iso8601DateAdapter::class)
    val sentAt: Date? = null,
    val topics: List<String> = emptyList(),
    val type: String = "",
    val opened: Boolean = false,
    val priority: Int = 0,
    val properties: Map<String, Any?>? = null,
    val queueId: String? = null
)
