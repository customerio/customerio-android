package io.customer.messaginginapp.inbox.jist

import io.customer.base.internal.InternalCustomerIOApi
import java.util.Date

/**
 * Jist-facing representation of an inbox message.
 *
 * Jist renders from `type` + `properties` (carried on the message). This type
 * preserves the message's typed/nested properties exactly: nested objects,
 * arrays, booleans, numbers and dates are kept as-is (no String flattening).
 *
 * This lives in the inbox module's Jist layer, NOT the gist transport layer:
 * the gist layer hands inbox messages over with `properties: Map<String, Any?>`
 * intact, and this adapter maps them to Jist types.
 */
@InternalCustomerIOApi
data class JistInboxMessage(
    val queueId: String,
    val deliveryId: String?,
    val type: String,
    val opened: Boolean,
    val priority: Int?,
    val sentAt: Date,
    val expiry: Date?,
    val topics: List<String>,
    /** Typed, possibly-nested properties used by Jist for rendering (no String flattening). */
    val properties: Map<String, Any?>
)
