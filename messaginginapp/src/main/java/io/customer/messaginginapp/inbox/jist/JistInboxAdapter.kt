package io.customer.messaginginapp.inbox.jist

import io.customer.base.internal.InternalCustomerIOApi
import io.customer.messaginginapp.gist.data.model.InboxMessage

/**
 * Maps domain [InboxMessage] objects into Jist render types ([JistInboxMessage]).
 *
 * Typed/nested properties are preserved. The domain
 * model already carries `properties: Map<String, Any?>`; this adapter passes the
 * map through WITHOUT flattening nested objects/arrays/bools/numbers/dates to
 * strings. We defensively deep-copy containers so the Jist type owns its data,
 * but value types are kept intact.
 */
@InternalCustomerIOApi
object JistInboxAdapter {

    fun toJist(message: InboxMessage): JistInboxMessage = JistInboxMessage(
        queueId = message.queueId,
        deliveryId = message.deliveryId,
        type = message.type,
        opened = message.opened,
        priority = message.priority,
        sentAt = message.sentAt,
        expiry = message.expiry,
        topics = message.topics,
        properties = deepCopyPreservingTypes(message.properties)
    )

    fun toJist(messages: List<InboxMessage>): List<JistInboxMessage> = messages.map { toJist(it) }

    /**
     * Recursively copies maps/lists so the returned structure is independent of
     * the source, while keeping every leaf value at its original type (Boolean,
     * Number, String, Date, etc.). Nothing is converted to String.
     */
    @Suppress("UNCHECKED_CAST")
    private fun deepCopyPreservingTypes(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { (k, v) ->
            k.toString() to deepCopyPreservingTypes(v)
        }

        is List<*> -> value.map { deepCopyPreservingTypes(it) }

        is Array<*> -> value.map { deepCopyPreservingTypes(it) }

        // Leaf value types (Boolean / Number / String / Date / null / etc.) are
        // preserved exactly as-is — no flattening.
        else -> value
    }

    private fun deepCopyPreservingTypes(map: Map<String, Any?>): Map<String, Any?> =
        map.mapValues { (_, v) -> deepCopyPreservingTypes(v) }
}
