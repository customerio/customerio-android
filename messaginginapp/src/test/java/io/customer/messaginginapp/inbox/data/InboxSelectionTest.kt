package io.customer.messaginginapp.inbox.data

import io.customer.messaginginapp.testutils.extension.createInboxMessage
import io.customer.messaginginapp.testutils.extension.dateDaysAgo
import io.customer.messaginginapp.testutils.extension.dateHoursAgo
import io.customer.messaginginapp.testutils.extension.dateNow
import java.util.Date
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class InboxSelectionTest {

    @Test
    fun select_givenTopicsWithPrefix_expectOnlyPrefixedKept() {
        val matching1 = createInboxMessage(deliveryId = "m1", topics = listOf("cio_inbox"))
        val matching2 = createInboxMessage(deliveryId = "m2", topics = listOf("cio_inbox_promos"))
        val nonMatching = createInboxMessage(deliveryId = "m3", topics = listOf("promotions"))

        val result = InboxSelection.select(listOf(matching1, matching2, nonMatching))

        result.map { it.deliveryId }.toSet() shouldBeEqualTo setOf("m1", "m2")
    }

    @Test
    fun select_givenPrefixCaseInsensitive_expectMatched() {
        val message = createInboxMessage(deliveryId = "m1", topics = listOf("CIO_INBOX_News"))

        val result = InboxSelection.select(listOf(message))

        result.map { it.deliveryId } shouldBeEqualTo listOf("m1")
    }

    @Test
    fun select_givenNullTopicFilter_expectAllNonExpiredKept() {
        val message = createInboxMessage(deliveryId = "m1", topics = listOf("anything"))

        val result = InboxSelection.select(listOf(message), topicPrefix = null)

        result.map { it.deliveryId } shouldBeEqualTo listOf("m1")
    }

    @Test
    fun select_givenExpiredMessage_expectDropped() {
        val now = Date()
        val expired = createInboxMessage(
            deliveryId = "expired",
            topics = listOf("cio_inbox"),
            expiry = dateHoursAgo(1)
        )
        val live = createInboxMessage(
            deliveryId = "live",
            topics = listOf("cio_inbox"),
            expiry = Date(now.time + 3_600_000L)
        )
        val noExpiry = createInboxMessage(deliveryId = "noexp", topics = listOf("cio_inbox"), expiry = null)

        val result = InboxSelection.select(listOf(expired, live, noExpiry), now = now)

        result.map { it.deliveryId }.toSet() shouldBeEqualTo setOf("live", "noexp")
    }

    @Test
    fun select_givenPriorities_expectAscendingThenSentAtDescending() {
        // priority asc (nulls last), then sentAt desc within equal priority.
        val p1Newer = createInboxMessage(deliveryId = "p1-new", topics = listOf("cio_inbox"), priority = 1, sentAt = dateNow())
        val p1Older = createInboxMessage(deliveryId = "p1-old", topics = listOf("cio_inbox"), priority = 1, sentAt = dateDaysAgo(2))
        val p5 = createInboxMessage(deliveryId = "p5", topics = listOf("cio_inbox"), priority = 5, sentAt = dateNow())
        val pNull = createInboxMessage(deliveryId = "pnull", topics = listOf("cio_inbox"), priority = null, sentAt = dateNow())

        val result = InboxSelection.select(listOf(p5, pNull, p1Older, p1Newer))

        result.map { it.deliveryId } shouldBeEqualTo listOf("p1-new", "p1-old", "p5", "pnull")
    }

    @Test
    fun select_givenEmptyTopics_expectExcludedWhenPrefixSet() {
        val noTopics = createInboxMessage(deliveryId = "none", topics = emptyList())

        val result = InboxSelection.select(listOf(noTopics))

        result shouldBeEqualTo emptyList()
    }
}
