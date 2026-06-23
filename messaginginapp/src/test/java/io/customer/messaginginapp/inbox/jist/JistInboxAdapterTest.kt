package io.customer.messaginginapp.inbox.jist

import io.customer.messaginginapp.testutils.extension.createInboxMessage
import java.util.Date
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.Test

class JistInboxAdapterTest {

    @Test
    fun toJist_givenScalarFields_expectMappedFaithfully() {
        val sentAt = Date()
        val message = createInboxMessage(
            queueId = "q1",
            deliveryId = "d1",
            type = "banner",
            opened = true,
            priority = 3,
            sentAt = sentAt,
            topics = listOf("cio_inbox")
        )

        val jist = JistInboxAdapter.toJist(message)

        jist.queueId shouldBeEqualTo "q1"
        jist.deliveryId shouldBeEqualTo "d1"
        jist.type shouldBeEqualTo "banner"
        jist.opened shouldBeEqualTo true
        jist.priority shouldBeEqualTo 3
        jist.sentAt shouldBeEqualTo sentAt
        jist.topics shouldBeEqualTo listOf("cio_inbox")
    }

    @Test
    fun toJist_givenNestedProperties_expectPreservedWithoutFlattening() {
        val nestedDate = Date(1_700_000_000_000L)
        val properties: Map<String, Any?> = mapOf(
            "title" to "Hello",
            "count" to 42,
            "ratio" to 3.14,
            "active" to true,
            "tags" to listOf("a", "b", true, 7),
            "nested" to mapOf(
                "level2" to mapOf(
                    "flag" to false,
                    "values" to listOf(1, 2, 3)
                )
            ),
            "publishedAt" to nestedDate,
            "nullable" to null
        )
        val message = createInboxMessage(properties = properties)

        val jist = JistInboxAdapter.toJist(message)

        // Scalars keep their types.
        jist.properties["title"] shouldBeEqualTo "Hello"
        jist.properties["count"] shouldBeEqualTo 42
        jist.properties["count"]!! shouldBeInstanceOf Int::class
        jist.properties["ratio"] shouldBeEqualTo 3.14
        jist.properties["active"] shouldBeEqualTo true
        jist.properties["active"]!! shouldBeInstanceOf Boolean::class

        // Arrays preserved with mixed element types.
        jist.properties["tags"] shouldBeEqualTo listOf("a", "b", true, 7)

        // Nested objects preserved as Maps (not stringified).
        val nested = jist.properties["nested"]
        nested shouldBeInstanceOf Map::class

        @Suppress("UNCHECKED_CAST")
        val level2 = (nested as Map<String, Any?>)["level2"] as Map<String, Any?>
        level2["flag"] shouldBeEqualTo false
        level2["values"] shouldBeEqualTo listOf(1, 2, 3)

        // Date preserved as Date.
        jist.properties["publishedAt"] shouldBeEqualTo nestedDate
        jist.properties["publishedAt"]!! shouldBeInstanceOf Date::class

        // Null preserved.
        jist.properties.containsKey("nullable") shouldBeEqualTo true
        jist.properties["nullable"] shouldBeEqualTo null
    }

    @Test
    fun toJist_givenNestedProperties_expectDeepCopyIndependentOfSource() {
        val mutableInner = mutableListOf(1, 2)
        val message = createInboxMessage(properties = mapOf("list" to mutableInner))

        val jist = JistInboxAdapter.toJist(message)
        mutableInner.add(3)

        // The Jist copy should not reflect mutation of the source list.
        jist.properties["list"] shouldBeEqualTo listOf(1, 2)
    }

    @Test
    fun toJist_givenList_expectAllMessagesMapped() {
        val messages = listOf(
            createInboxMessage(deliveryId = "a"),
            createInboxMessage(deliveryId = "b")
        )

        val result = JistInboxAdapter.toJist(messages)

        result.map { it.deliveryId } shouldBeEqualTo listOf("a", "b")
    }
}
