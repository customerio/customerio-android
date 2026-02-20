package io.customer.messaginginapp.gist.data.model

import io.customer.messaginginapp.gist.data.model.response.InboxMessageFactory
import io.customer.messaginginapp.gist.data.model.response.InboxMessageResponse
import java.util.Date
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test

class InboxMessageMappingTest {

    @Test
    fun fromResponse_givenValidResponse_expectDomainModel() {
        val response = InboxMessageResponse(
            queueId = "queue-123",
            deliveryId = "delivery-456",
            sentAt = Date(),
            topics = listOf("promotions", "updates"),
            opened = true,
            priority = 5
        )

        val result = requireNotNull(InboxMessageFactory.fromResponse(response))

        result.queueId shouldBeEqualTo "queue-123"
        result.deliveryId shouldBeEqualTo "delivery-456"
        result.topics shouldBeEqualTo listOf("promotions", "updates")
        result.opened shouldBeEqualTo true
        result.priority shouldBeEqualTo 5
    }

    @Test
    fun fromResponse_givenMinimalResponse_expectDefaults() {
        val response = InboxMessageResponse(
            queueId = "queue-123",
            deliveryId = "delivery-456",
            sentAt = Date(),
            topics = null,
            opened = null,
            priority = null
        )

        val result = requireNotNull(InboxMessageFactory.fromResponse(response))

        // Verify default values for nullable fields
        result.queueId shouldBeEqualTo "queue-123"
        result.deliveryId shouldBeEqualTo "delivery-456"
        result.topics shouldBeEqualTo emptyList()
        result.opened shouldBeEqualTo false
        result.priority shouldBeEqualTo null
        result.type shouldBeEqualTo ""
        result.properties shouldBeEqualTo emptyMap()
    }

    @Test
    fun fromResponse_givenNullQueueId_expectNull() {
        val response = InboxMessageResponse(
            queueId = null,
            deliveryId = "delivery-456",
            sentAt = Date()
        )

        val result = InboxMessageFactory.fromResponse(response)

        result shouldBeEqualTo null
    }

    @Test
    fun fromResponse_givenNullSentAt_expectNull() {
        val response = InboxMessageResponse(
            queueId = "queue-123",
            sentAt = null
        )

        val result = InboxMessageFactory.fromResponse(response)

        result shouldBeEqualTo null
    }

    @Test
    fun fromResponse_givenValidProperties_expectPropertiesMapped() {
        val response = InboxMessageResponse(
            queueId = "queue-123",
            sentAt = Date(),
            properties = mapOf(
                "title" to "Welcome",
                "count" to 42.0,
                "enabled" to true
            )
        )

        val result = requireNotNull(InboxMessageFactory.fromResponse(response))

        result.properties.shouldNotBeNull()
        result.properties["title"] shouldBeEqualTo "Welcome"
        result.properties["count"] shouldBeEqualTo 42.0
        result.properties["enabled"] shouldBeEqualTo true
    }

    @Test
    fun fromMap_givenValidMap_expectDomainModel() {
        val map = mapOf(
            "queueId" to "queue-123",
            "deliveryId" to "delivery-456",
            "sentAt" to System.currentTimeMillis(),
            "topics" to listOf("promotions", "updates"),
            "type" to "notification",
            "opened" to true,
            "priority" to 5,
            "properties" to mapOf("key" to "value")
        )

        val result = requireNotNull(InboxMessageFactory.fromMap(map))

        result.queueId shouldBeEqualTo "queue-123"
        result.deliveryId shouldBeEqualTo "delivery-456"
        result.topics shouldBeEqualTo listOf("promotions", "updates")
        result.type shouldBeEqualTo "notification"
        result.opened shouldBeEqualTo true
        result.priority shouldBeEqualTo 5
        result.properties shouldBeEqualTo mapOf("key" to "value")
    }

    @Test
    fun fromMap_givenMissingQueueId_expectNull() {
        val map = mapOf(
            "deliveryId" to "delivery-456",
            "sentAt" to System.currentTimeMillis()
        )

        val result = InboxMessageFactory.fromMap(map)

        result shouldBeEqualTo null
    }

    @Test
    fun fromMap_givenMissingSentAt_expectNull() {
        val map = mapOf(
            "queueId" to "queue-123",
            "deliveryId" to "delivery-456"
        )

        val result = InboxMessageFactory.fromMap(map)

        result shouldBeEqualTo null
    }
}
