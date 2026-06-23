package io.customer.messaginginapp.inbox.data

import io.customer.messaginginapp.gist.data.model.InboxMessage
import java.util.Date

/**
 * Dedicated visual-inbox selection query.
 *
 * This is SEPARATE from the headless [io.customer.messaginginapp.inbox.NotificationInbox]
 * ordering (filterMessagesByTopic, sentAt-desc only). Do not route headless
 * callers through here and vice versa.
 *
 * Selection rules:
 * - Topic filter uses the `cio_inbox` PREFIX (not exact match). A null topic
 *   means "no topic filter".
 * - Drop messages whose `expiry` has passed at read time.
 * - Sort priority ascending (lower value = higher priority; nulls last),
 *   then sentAt descending.
 */
internal object InboxSelection {

    const val VISUAL_INBOX_TOPIC_PREFIX = "cio_inbox"

    fun select(
        messages: List<InboxMessage>,
        topicPrefix: String? = VISUAL_INBOX_TOPIC_PREFIX,
        now: Date = Date()
    ): List<InboxMessage> {
        return messages
            .asSequence()
            .filter { message -> topicMatches(message, topicPrefix) }
            .filter { message -> !isExpired(message, now) }
            .sortedWith(
                compareBy(nullsLast<Int>()) { message: InboxMessage -> message.priority }
                    .thenByDescending { message -> message.sentAt }
            )
            .toList()
    }

    private fun topicMatches(message: InboxMessage, topicPrefix: String?): Boolean {
        if (topicPrefix == null) return true
        return message.topics.any { it.startsWith(topicPrefix, ignoreCase = true) }
    }

    private fun isExpired(message: InboxMessage, now: Date): Boolean {
        val expiry = message.expiry ?: return false
        return expiry.before(now)
    }
}
