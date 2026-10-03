package io.customer.messaginginapp.gist.data.sse

import io.customer.messaginginapp.testutils.core.JUnitTest
import java.io.EOFException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okio.Buffer
import okio.Pipe
import okio.buffer
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SseEventReaderTest : JUnitTest() {
    @Test
    fun testProcess_whenStreamContainsSupportedFields_thenEmitsParsedEvents() {
        val events = mutableListOf<Pair<String?, String>>()
        val stream =
            Buffer().writeUtf8(
                """
                : heartbeat comment
                event: connected
                data: {}

                id: ignored-id
                event: messages
                data: {"first": true,
                data: "second": true}

                """.trimIndent() + "\n"
            )

        SseEventReader(stream) { type, data -> events += type to data }.process()

        events shouldBeEqualTo
            listOf(
                "connected" to "{}",
                "messages" to "{\"first\": true,\n\"second\": true}"
            )
    }

    @Test
    fun testProcess_whenStreamStartsWithBomAndUsesCrLf_thenEmitsEvent() {
        val events = mutableListOf<Pair<String?, String>>()
        val stream = Buffer().writeUtf8("\uFEFFevent: heartbeat\r\ndata: ping\r\n\r\n")

        SseEventReader(stream) { type, data -> events += type to data }.process()

        events shouldBeEqualTo listOf("heartbeat" to "ping")
    }

    @Test
    fun testProcess_whenStreamUsesCrOnly_thenEmitsEvent() {
        val events = mutableListOf<Pair<String?, String>>()
        val stream = Buffer().writeUtf8("event: heartbeat\rdata: ping\r\r")

        SseEventReader(stream) { type, data -> events += type to data }.process()

        events shouldBeEqualTo listOf("heartbeat" to "ping")
    }

    @Test
    fun testProcess_whenOpenStreamUsesCrOnly_thenEmitsWithoutWaitingForNextByte() {
        val pipe = Pipe(1024)
        val received = CountDownLatch(1)
        val reader =
            Thread {
                SseEventReader(pipe.source.buffer()) { type, data ->
                    if (type == "heartbeat" && data == "ping") received.countDown()
                }.process()
            }
        reader.start()

        try {
            pipe.sink.buffer().use { sink ->
                sink.writeUtf8("event: heartbeat\rdata: ping\r\r")
                sink.flush()
                assertTrue(received.await(1, TimeUnit.SECONDS))
            }
        } finally {
            pipe.sink.close()
            reader.join(1_000)
        }
    }

    @Test
    fun testProcess_whenLastEventHasNoBlankLine_thenThrowsForIncompleteEvent() {
        val events = mutableListOf<Pair<String?, String>>()
        val stream = Buffer().writeUtf8("event: messages\ndata: incomplete")

        val error =
            assertThrows<EOFException> {
                SseEventReader(stream) { type, data -> events += type to data }.process()
            }

        events shouldBeEqualTo emptyList()
        error.message shouldBeEqualTo "SSE stream ended before the event delimiter"
    }

    @Test
    fun testProcess_whenOnlyEventTypeIsInterrupted_thenThrowsForIncompleteEvent() {
        val events = mutableListOf<Pair<String?, String>>()
        val stream = Buffer().writeUtf8("event: messages\n")

        assertThrows<EOFException> {
            SseEventReader(stream) { type, data -> events += type to data }.process()
        }

        events shouldBeEqualTo emptyList()
    }
}
