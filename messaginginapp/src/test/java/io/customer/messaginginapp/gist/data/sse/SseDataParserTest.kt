package io.customer.messaginginapp.gist.data.sse

import com.google.gson.Gson
import io.customer.messaginginapp.testutils.core.JUnitTest
import io.mockk.mockk
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.junit.jupiter.api.Test

class SseDataParserTest : JUnitTest() {

    private val sseLogger = mockk<InAppSseLogger>(relaxed = true)
    private val gson = Gson()
    private val parser = SseDataParser(sseLogger, gson)

    @Test
    fun testParseMessages_givenValidJson_thenReturnsMessages() {
        val json = """
            [
                {
                    "messageId": "msg1",
                    "priority": 1,
                    "queueId": "queue1",
                    "properties": {
                        "title": "Test Message",
                        "body": "Test Body",
                        "type": "modal"
                    }
                },
                {
                    "messageId": "msg2",
                    "priority": 2,
                    "queueId": "queue2",
                    "properties": {
                        "title": "Test Message 2",
                        "body": "Test Body 2",
                        "type": "inline"
                    }
                }
            ]
        """.trimIndent()

        val result = parser.parseInAppMessages(json)

        result.shouldHaveSize(2)
        result[0].messageId.shouldBeEqualTo("msg1")
        result[0].queueId.shouldBeEqualTo("queue1")
        result[1].messageId.shouldBeEqualTo("msg2")
        result[1].queueId.shouldBeEqualTo("queue2")
    }

    @Test
    fun testParseMessages_givenEmptyArray_thenReturnsEmptyList() {
        val json = "[]"

        val result = parser.parseInAppMessages(json)

        result.shouldBeEmpty()
    }

    @Test
    fun testParseMessages_givenBlankData_thenReturnsEmptyList() {
        val result = parser.parseInAppMessages("")

        result.shouldBeEmpty()
    }

    @Test
    fun testParseMessages_givenWhitespaceOnly_thenReturnsEmptyList() {
        val result = parser.parseInAppMessages("   ")

        result.shouldBeEmpty()
    }

    @Test
    fun testParseMessages_givenInvalidJson_thenReturnsEmptyList() {
        val invalidJson = "{ invalid json }"

        val result = parser.parseInAppMessages(invalidJson)

        result.shouldBeEmpty()
    }

    @Test
    fun testParseMessages_givenMalformedJson_thenReturnsEmptyList() {
        val malformedJson = "[{ messageId: }]"

        val result = parser.parseInAppMessages(malformedJson)

        result.shouldBeEmpty()
    }

    @Test
    fun testParseMessages_givenNull_thenReturnsEmptyList() {
        val result = parser.parseInAppMessages("null")

        result.shouldBeEmpty()
    }

    @Test
    fun testParseMessages_givenSingleMessage_thenReturnsOneMessage() {
        val json = """
            [
                {
                    "messageId": "single-msg",
                    "priority": 1,
                    "queueId": "single-queue",
                    "properties": {
                        "title": "Single Message",
                        "body": "Single Body",
                        "type": "modal"
                    }
                }
            ]
        """.trimIndent()

        val result = parser.parseInAppMessages(json)

        result.shouldHaveSize(1)
        result[0].messageId.shouldBeEqualTo("single-msg")
        result[0].queueId.shouldBeEqualTo("single-queue")
    }

    @Test
    fun testParseHeartbeatTimeout_givenValidJson_thenReturnsTimeoutInMs() {
        val json = """{"heartbeat": 30}"""

        val result = parser.parseHeartbeatTimeout(json)

        result.shouldBeEqualTo(30000L) // 30 seconds * 1000 = 30000ms
    }

    @Test
    fun testParseHeartbeatTimeout_givenZeroHeartbeat_thenReturnsZero() {
        val json = """{"heartbeat": 0}"""

        val result = parser.parseHeartbeatTimeout(json)

        result.shouldBeEqualTo(0L)
    }

    @Test
    fun testParseHeartbeatTimeout_givenBlankData_thenReturnsDefault() {
        val result = parser.parseHeartbeatTimeout("")

        result.shouldBeEqualTo(30000L) // DEFAULT_HEARTBEAT_TIMEOUT_MS
    }

    @Test
    fun testParseHeartbeatTimeout_givenWhitespaceOnly_thenReturnsDefault() {
        val result = parser.parseHeartbeatTimeout("   ")

        result.shouldBeEqualTo(30000L) // DEFAULT_HEARTBEAT_TIMEOUT_MS
    }

    @Test
    fun testParseHeartbeatTimeout_givenInvalidJson_thenReturnsDefault() {
        val invalidJson = "{ invalid json }"

        val result = parser.parseHeartbeatTimeout(invalidJson)

        result.shouldBeEqualTo(30000L) // DEFAULT_HEARTBEAT_TIMEOUT_MS
    }

    @Test
    fun testParseHeartbeatTimeout_givenMissingHeartbeatField_thenReturnsDefault() {
        val json = """{"other": "value"}"""

        val result = parser.parseHeartbeatTimeout(json)

        result.shouldBeEqualTo(30000L) // DEFAULT_HEARTBEAT_TIMEOUT_MS
    }

    @Test
    fun testParseHeartbeatTimeout_givenNullHeartbeat_thenReturnsDefault() {
        val json = """{"heartbeat": null}"""

        val result = parser.parseHeartbeatTimeout(json)

        result.shouldBeEqualTo(30000L) // DEFAULT_HEARTBEAT_TIMEOUT_MS
    }

    @Test
    fun testParseHeartbeatTimeout_givenNonNumericHeartbeat_thenReturnsDefault() {
        val json = """{"heartbeat": "not-a-number"}"""

        val result = parser.parseHeartbeatTimeout(json)

        result.shouldBeEqualTo(30000L) // DEFAULT_HEARTBEAT_TIMEOUT_MS
    }

    @Test
    fun testParseHeartbeatTimeout_givenLargeHeartbeat_thenReturnsCorrectValue() {
        val json = """{"heartbeat": 300}"""

        val result = parser.parseHeartbeatTimeout(json)

        result.shouldBeEqualTo(300000L) // 300 seconds * 1000 = 300000ms
    }

    @Test
    fun testParseInboxMessages_givenValidJson_thenReturnsInboxMessages() {
        val json = """
            [
                {
                    "deliveryId": "delivery_Inbox1",
                    "expiry": "2026-01-30T12:00:00.000000Z",
                    "sentAt": "2026-01-29T12:00:00.000000Z",
                    "topics": ["topic1", "topic2"],
                    "type": "notification",
                    "opened": false,
                    "priority": 1,
                    "properties": {
                        "body": "Body",
                        "title": "Title"
                    },
                    "queueId": "queue-abcd-1234-efgh"
                },
                {
                    "deliveryId": "Delivery_inbox2",
                    "expiry": "2026-02-01T12:00:00.000000Z",
                    "sentAt": "2026-01-30T12:00:00.000000Z",
                    "topics": ["topic3"],
                    "type": "announcement",
                    "opened": true,
                    "priority": 2,
                    "queueId": "queue-pqrs-5678-tuvw"
                }
            ]
        """.trimIndent()

        val result = parser.parseInboxMessages(json)

        result.shouldHaveSize(2)
        result[0].deliveryId.shouldBeEqualTo("delivery_Inbox1")
        result[0].type.shouldBeEqualTo("notification")
        result[0].opened.shouldBeEqualTo(false)
        result[1].deliveryId.shouldBeEqualTo("Delivery_inbox2")
        result[1].type.shouldBeEqualTo("announcement")
        result[1].opened.shouldBeEqualTo(true)
    }

    @Test
    fun testParseInboxMessages_givenEmptyArray_thenReturnsEmptyList() {
        val json = "[]"

        val result = parser.parseInboxMessages(json)

        result.shouldBeEmpty()
    }

    @Test
    fun testParseInboxMessages_givenBlankData_thenReturnsEmptyList() {
        val result = parser.parseInboxMessages("")

        result.shouldBeEmpty()
    }

    @Test
    fun testParseInboxMessages_givenWhitespaceOnly_thenReturnsEmptyList() {
        val result = parser.parseInboxMessages("   ")

        result.shouldBeEmpty()
    }

    @Test
    fun testParseInboxMessages_givenInvalidJson_thenReturnsEmptyList() {
        val invalidJson = "{ invalid json }"

        val result = parser.parseInboxMessages(invalidJson)

        result.shouldBeEmpty()
    }

    @Test
    fun testParseInboxMessages_givenMalformedJson_thenReturnsEmptyList() {
        val malformedJson = "[{ deliveryId: }]"

        val result = parser.parseInboxMessages(malformedJson)

        result.shouldBeEmpty()
    }

    @Test
    fun testParseInboxMessages_givenNull_thenReturnsEmptyList() {
        val result = parser.parseInboxMessages("null")

        result.shouldBeEmpty()
    }

    @Test
    fun testParseInboxMessages_givenSingleMessage_thenReturnsOneMessage() {
        val json = """
            [
                {
                    "deliveryId": "single-inbox-msg",
                    "expiry": "2026-02-01T12:00:00.000000Z",
                    "sentAt": "2026-01-30T12:00:00.000000Z",
                    "topics": [],
                    "type": "alert",
                    "opened": false,
                    "priority": 5,
                    "queueId": "queue-ijkl-9012-mnop"
                }
            ]
        """.trimIndent()

        val result = parser.parseInboxMessages(json)

        result.shouldHaveSize(1)
        result[0].deliveryId.shouldBeEqualTo("single-inbox-msg")
        result[0].type.shouldBeEqualTo("alert")
        result[0].opened.shouldBeEqualTo(false)
        result[0].priority.shouldBeEqualTo(5)
    }
}
