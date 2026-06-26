package io.customer.messaginginbox

import io.customer.messaginginapp.inbox.jist.JistInboxMessage
import java.util.Date
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldBeTrue
import org.junit.Test

/**
 * Unit tests for [InboxJistDecoder] (raw data layer JSON/maps -> Jist render types) and the
 * unread-count accessor. Pure logic — no Compose runtime.
 */
class InboxJistDecoderTest {

    private fun jistMessage(
        queueId: String = "q",
        type: String = "basic",
        opened: Boolean = false,
        properties: Map<String, Any?> = emptyMap()
    ): JistInboxMessage = JistInboxMessage(
        queueId = queueId,
        deliveryId = null,
        type = type,
        opened = opened,
        priority = null,
        sentAt = Date(0),
        expiry = null,
        topics = emptyList(),
        properties = properties
    )

    // --- decodeTemplates ---

    private val validTemplatesJson = """
        {
          "${'$'}schema": "https://example.com/schema",
          "basic": [ { "version": "1", "root": { "type": "text", "name": "title" } } ],
          "card":  [ { "version": "1", "root": { "type": "layout", "direction": "vertical" } } ]
        }
    """.trimIndent()

    @Test
    fun decodeTemplates_givenValidRegistry_expectKeysDecodedAndMetadataDropped() {
        val templates = InboxJistDecoder.decodeTemplates(validTemplatesJson)

        templates.keys shouldBeEqualTo setOf("basic", "card")
        templates["basic"]!!.size shouldBeEqualTo 1
        templates["basic"]!!.first().version shouldBeEqualTo "1"
    }

    @Test
    fun decodeTemplates_givenNullOrBlank_expectEmptyMap() {
        InboxJistDecoder.decodeTemplates(null) shouldBeEqualTo emptyMap()
        InboxJistDecoder.decodeTemplates("   ") shouldBeEqualTo emptyMap()
    }

    @Test
    fun decodeTemplates_givenMalformedJson_expectEmptyMap() {
        InboxJistDecoder.decodeTemplates("not json {") shouldBeEqualTo emptyMap()
    }

    @Test
    fun decodeTemplates_givenNonObjectRoot_expectEmptyMap() {
        InboxJistDecoder.decodeTemplates("[1,2,3]") shouldBeEqualTo emptyMap()
    }

    // --- toJsonObject / decodeData ---

    @Test
    fun toJsonObject_givenNullOrEmpty_expectEmptyObject() {
        InboxJistDecoder.toJsonObject(null) shouldBeEqualTo JsonObject(emptyMap())
        InboxJistDecoder.toJsonObject(emptyMap()) shouldBeEqualTo JsonObject(emptyMap())
    }

    @Test
    fun toJsonObject_givenTypedNestedProperties_expectTypesPreserved() {
        val map = mapOf(
            "title" to "Hello",
            "count" to 3,
            "flag" to true,
            "nested" to mapOf("inner" to "v"),
            "list" to listOf(1, 2)
        )

        val json = InboxJistDecoder.toJsonObject(map)

        (json["title"] as JsonPrimitive).content shouldBeEqualTo "Hello"
        (json["count"] as JsonPrimitive).intOrNull shouldBeEqualTo 3
        (json["flag"] as JsonPrimitive).booleanOrNull shouldBeEqualTo true
        json["nested"].shouldBeInstanceOf<JsonObject>()
        json["list"].shouldBeInstanceOf<JsonArray>()
        (json["list"] as JsonArray).size shouldBeEqualTo 2
    }

    @Test
    fun decodeData_givenMessageProperties_expectMappedToJsonElements() {
        val message = jistMessage(properties = mapOf("title" to "Hi"))

        val data = InboxJistDecoder.decodeData(message)

        data.containsKey("title").shouldBeTrue()
        (data.getValue("title") as JsonPrimitive).content shouldBeEqualTo "Hi"
    }

    @Test
    fun decodeData_givenEmptyProperties_expectEmpty() {
        InboxJistDecoder.decodeData(jistMessage(properties = emptyMap())).isEmpty().shouldBeTrue()
    }

    @Test
    fun toJsonObject_givenDateValue_expectStringFallback() {
        val json = InboxJistDecoder.toJsonObject(mapOf("when" to Date(0)))
        json.containsKey("when").shouldBeTrue()
        // Rendered as a string (ISO instant), not dropped.
        (json["when"] as JsonPrimitive).isString.shouldBeTrue()
        json.containsKey("missing") shouldBeEqualTo false
    }

    // --- unopenedInboxCount ---

    @Test
    fun unopenedInboxCount_givenNoMessages_expectZero() {
        unopenedInboxCount(emptyList()) shouldBeEqualTo 0
    }

    @Test
    fun unopenedInboxCount_givenAllOpened_expectZero() {
        unopenedInboxCount(
            listOf(jistMessage(opened = true), jistMessage(opened = true))
        ) shouldBeEqualTo 0
    }

    @Test
    fun unopenedInboxCount_givenMixed_expectOnlyUnopenedCounted() {
        unopenedInboxCount(
            listOf(
                jistMessage(queueId = "a", opened = false),
                jistMessage(queueId = "b", opened = true),
                jistMessage(queueId = "c", opened = false)
            )
        ) shouldBeEqualTo 2
    }

    // --- formatRelativeDate (Jist formatDate hook) ---

    // Epoch millis for 2026-06-20T12:00:00Z (value is irrelevant: this test exercises the
    // parse-failure fallback, which returns before DateUtils/now is used).
    private val now: Long = 1_781_006_400_000L

    @Test
    fun formatRelativeDate_givenUnparseable_expectRawValueReturned() {
        // Valid timestamps are formatted by android.text.format.DateUtils (system-localized), which
        // isn't exercised in plain JVM unit tests. We verify the parse-failure fallback, which
        // returns the raw input before ever touching DateUtils.
        InboxJistDecoder.formatRelativeDate("not-a-date", name = "sentAt", now = now) shouldBeEqualTo "not-a-date"
    }
}
