package io.customer.messaginginapp.gist.data.sse

import io.customer.messaginginapp.testutils.core.JUnitTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotBeEqualTo
import org.junit.jupiter.api.Test

class SseEventTest : JUnitTest() {

    @Test
    fun testConnectionOpenEvent_shouldBeSingleton() {
        val event1 = ConnectionOpenEvent
        val event2 = ConnectionOpenEvent

        event1.shouldBeEqualTo(event2)
        event1 shouldBeEqualTo ConnectionOpenEvent
    }

    @Test
    fun testServerEvent_constructor_thenSetsPropertiesCorrectly() {
        val event = ServerEvent("messages", "{\"data\": \"test\"}")

        event.eventType.shouldBeEqualTo("messages")
        event.data.shouldBeEqualTo("{\"data\": \"test\"}")
    }

    @Test
    fun testServerEvent_equals_thenComparesCorrectly() {
        val event1 = ServerEvent("messages", "{\"data\": \"test\"}")
        val event2 = ServerEvent("messages", "{\"data\": \"test\"}")
        val event3 = ServerEvent("heartbeat", "{\"data\": \"test\"}")

        event1.shouldBeEqualTo(event2)
        event1.shouldNotBeEqualTo(event3)
    }

    @Test
    fun testServerEvent_toString_thenContainsProperties() {
        val event = ServerEvent("messages", "{\"data\": \"test\"}")
        val string = event.toString()

        string.shouldContain("messages")
        string.shouldContain("{\"data\": \"test\"}")
    }

    @Test
    fun testServerEvent_copy_thenCreatesNewInstance() {
        val original = ServerEvent("messages", "{\"data\": \"test\"}")
        val copied = original.copy(eventType = "heartbeat")

        copied.eventType.shouldBeEqualTo("heartbeat")
        copied.data.shouldBeEqualTo("{\"data\": \"test\"}")
        original.eventType.shouldBeEqualTo("messages") // Original unchanged
    }

    @Test
    fun testServerEvent_constants_thenHaveCorrectValues() {
        ServerEvent.CONNECTED.shouldBeEqualTo("connected")
        ServerEvent.HEARTBEAT.shouldBeEqualTo("heartbeat")
        ServerEvent.MESSAGES.shouldBeEqualTo("messages")
        ServerEvent.TTL_EXCEEDED.shouldBeEqualTo("ttl_exceeded")
    }

    @Test
    fun testSseEvent_sealedInterface_thenAllowsPatternMatching() {
        val connectionEvent: SseEvent = ConnectionOpenEvent
        val serverEvent: SseEvent = ServerEvent("test", "data")

        when (connectionEvent) {
            is ConnectionOpenEvent -> {
                // Should reach here
                true.shouldBeEqualTo(true)
            }
            is ServerEvent -> {
                // Should not reach here
                false.shouldBeEqualTo(true)
            }
        }

        when (serverEvent) {
            is ConnectionOpenEvent -> {
                // Should not reach here
                false.shouldBeEqualTo(true)
            }
            is ServerEvent -> {
                // Should reach here
                serverEvent.eventType.shouldBeEqualTo("test")
                serverEvent.data.shouldBeEqualTo("data")
            }
        }
    }
}
