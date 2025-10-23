package io.customer.messaginginapp.gist.data.sse

import com.google.gson.Gson
import io.customer.messaginginapp.testutils.core.JUnitTest
import io.customer.sdk.core.util.Logger
import io.mockk.mockk
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.junit.jupiter.api.Test

class SseEventParserTest : JUnitTest() {

    private val logger = mockk<Logger>(relaxed = true)
    private val gson = Gson()
    private val parser = SseEventParser(logger, gson)

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

        val result = parser.parseMessages(json)

        result.shouldHaveSize(2)
        result[0].messageId.shouldBeEqualTo("msg1")
        result[0].queueId.shouldBeEqualTo("queue1")
        result[1].messageId.shouldBeEqualTo("msg2")
        result[1].queueId.shouldBeEqualTo("queue2")
    }

    @Test
    fun testParseMessages_givenEmptyArray_thenReturnsEmptyList() {
        val json = "[]"

        val result = parser.parseMessages(json)

        result.shouldBeEmpty()
    }

    @Test
    fun testParseMessages_givenBlankData_thenReturnsEmptyList() {
        val result = parser.parseMessages("")

        result.shouldBeEmpty()
    }

    @Test
    fun testParseMessages_givenWhitespaceOnly_thenReturnsEmptyList() {
        val result = parser.parseMessages("   ")

        result.shouldBeEmpty()
    }

    @Test
    fun testParseMessages_givenInvalidJson_thenReturnsEmptyList() {
        val invalidJson = "{ invalid json }"

        val result = parser.parseMessages(invalidJson)

        result.shouldBeEmpty()
    }

    @Test
    fun testParseMessages_givenMalformedJson_thenReturnsEmptyList() {
        val malformedJson = "[{ messageId: }]"

        val result = parser.parseMessages(malformedJson)

        result.shouldBeEmpty()
    }

    @Test
    fun testParseMessages_givenNull_thenReturnsEmptyList() {
        val result = parser.parseMessages("null")

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

        val result = parser.parseMessages(json)

        result.shouldHaveSize(1)
        result[0].messageId.shouldBeEqualTo("single-msg")
        result[0].queueId.shouldBeEqualTo("single-queue")
    }
}
