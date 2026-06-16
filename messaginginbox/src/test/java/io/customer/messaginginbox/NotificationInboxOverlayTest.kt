package io.customer.messaginginbox

import io.customer.messaginginapp.gist.data.model.InboxMessage
import java.util.Date
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

/**
 * Unit tests for the pure (non-Compose) logic backing [NotificationInboxOverlay]:
 * the unread badge count and the placeholder row title derivation.
 *
 * The overlay's Compose/UI behavior (panel toggle, hide-when-empty, listener lifecycle) is not
 * unit-tested here: it lives in `@Composable`/`@State`-driven code that needs a Compose UI-test
 * runtime, and the headless `NotificationInbox` is a final class with an internal constructor
 * (so it cannot be faked from this module). These tests cover the logic that can be exercised
 * directly without that machinery.
 */
class NotificationInboxOverlayTest {

    private fun message(
        queueId: String = "q",
        deliveryId: String? = null,
        opened: Boolean = false,
        properties: Map<String, Any?> = emptyMap()
    ): InboxMessage = InboxMessage(
        queueId = queueId,
        deliveryId = deliveryId,
        expiry = null,
        sentAt = Date(),
        topics = emptyList(),
        type = "test",
        opened = opened,
        priority = null,
        properties = properties
    )

    @Test
    fun unreadInboxCount_givenNoMessages_expectZero() {
        unreadInboxCount(emptyList()) shouldBeEqualTo 0
    }

    @Test
    fun unreadInboxCount_givenAllOpened_expectZero() {
        val messages = listOf(message(opened = true), message(opened = true))
        unreadInboxCount(messages) shouldBeEqualTo 0
    }

    @Test
    fun unreadInboxCount_givenMixed_expectOnlyUnopenedCounted() {
        val messages = listOf(
            message(queueId = "a", opened = false),
            message(queueId = "b", opened = true),
            message(queueId = "c", opened = false)
        )
        unreadInboxCount(messages) shouldBeEqualTo 2
    }

    @Test
    fun inboxTitle_givenTitleProperty_expectTitle() {
        message(properties = mapOf("title" to "Hello")).inboxTitle() shouldBeEqualTo "Hello"
    }

    @Test
    fun inboxTitle_givenNoTitleButSubject_expectSubject() {
        message(properties = mapOf("subject" to "A subject")).inboxTitle() shouldBeEqualTo "A subject"
    }

    @Test
    fun inboxTitle_givenBlankTitle_expectFallThroughToNextKey() {
        val message = message(properties = mapOf("title" to "   ", "subject" to "Subject"))
        message.inboxTitle() shouldBeEqualTo "Subject"
    }

    @Test
    fun inboxTitle_givenNoTitleLikeProperty_expectDeliveryId() {
        message(queueId = "q1", deliveryId = "d1").inboxTitle() shouldBeEqualTo "d1"
    }

    @Test
    fun inboxTitle_givenNoTitleLikePropertyAndNoDeliveryId_expectQueueId() {
        message(queueId = "q1", deliveryId = null).inboxTitle() shouldBeEqualTo "q1"
    }
}
