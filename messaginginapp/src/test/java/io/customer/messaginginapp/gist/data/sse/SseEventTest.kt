package io.customer.messaginginapp.gist.data.sse

import io.customer.messaginginapp.testutils.core.JUnitTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEqualTo
import org.amshove.kluent.shouldContain
import org.junit.jupiter.api.Test

class SseEventTest : JUnitTest() {

    @Test
    fun testSseEvent_constructor_thenSetsPropertiesCorrectly() {
        val event = SseEvent("messages", "{\"data\": \"test\"}")

        event.eventType.shouldBeEqualTo("messages")
        event.data.shouldBeEqualTo("{\"data\": \"test\"}")
    }

    @Test
    fun testSseEvent_equals_thenComparesCorrectly() {
        val event1 = SseEvent("messages", "{\"data\": \"test\"}")
        val event2 = SseEvent("messages", "{\"data\": \"test\"}")
        val event3 = SseEvent("heartbeat", "{\"data\": \"test\"}")

        event1.shouldBeEqualTo(event2)
        event1.shouldNotBeEqualTo(event3)
    }

    @Test
    fun testSseEvent_toString_thenContainsProperties() {
        val event = SseEvent("messages", "{\"data\": \"test\"}")
        val string = event.toString()

        string.shouldContain("messages")
        string.shouldContain("{\"data\": \"test\"}")
    }

    @Test
    fun testSseEvent_copy_thenCreatesNewInstance() {
        val original = SseEvent("messages", "{\"data\": \"test\"}")
        val copied = original.copy(eventType = "heartbeat")

        copied.eventType.shouldBeEqualTo("heartbeat")
        copied.data.shouldBeEqualTo("{\"data\": \"test\"}")
        original.eventType.shouldBeEqualTo("messages") // Original unchanged
    }
}
