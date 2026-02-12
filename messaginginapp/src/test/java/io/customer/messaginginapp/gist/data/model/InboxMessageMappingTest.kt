package io.customer.messaginginapp.gist.data.model

import io.customer.messaginginapp.gist.data.model.response.InboxMessageResponse
import io.customer.messaginginapp.gist.data.model.response.toDomain
import java.util.Date
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test

class InboxMessageMappingTest {

    @Test
    fun toDomain_givenValidResponse_expectDomainModel() {
        val response = InboxMessageResponse(
            queueId = "queue-123",
            deliveryId = "delivery-456",
            sentAt = Date(),
            topics = listOf("promotions", "updates"),
            opened = true,
            priority = 5
        )

        val result = requireNotNull(response.toDomain())

        result.queueId shouldBeEqualTo "queue-123"
        result.deliveryId shouldBeEqualTo "delivery-456"
        result.topics shouldBeEqualTo listOf("promotions", "updates")
        result.opened shouldBeEqualTo true
        result.priority shouldBeEqualTo 5
    }

    @Test
    fun toDomain_givenMinimalResponse_expectDefaults() {
        val response = InboxMessageResponse(
            queueId = "queue-123",
            deliveryId = "delivery-456",
            sentAt = Date(),
            topics = null,
            opened = null,
            priority = null
        )

        val result = requireNotNull(response.toDomain())

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
    fun toDomain_givenNullQueueId_expectNull() {
        val response = InboxMessageResponse(
            queueId = null,
            deliveryId = "delivery-456",
            sentAt = Date()
        )

        val result = response.toDomain()

        result shouldBeEqualTo null
    }

    @Test
    fun toDomain_givenNullSentAt_expectNull() {
        val response = InboxMessageResponse(
            queueId = "queue-123",
            sentAt = null
        )

        val result = response.toDomain()

        result shouldBeEqualTo null
    }

    @Test
    fun toDomain_givenValidProperties_expectPropertiesMapped() {
        val response = InboxMessageResponse(
            queueId = "queue-123",
            sentAt = Date(),
            properties = mapOf(
                "title" to "Welcome",
                "count" to 42.0,
                "enabled" to true
            )
        )

        val result = requireNotNull(response.toDomain())

        result.properties.shouldNotBeNull()
        result.properties["title"] shouldBeEqualTo "Welcome"
        result.properties["count"] shouldBeEqualTo 42.0
        result.properties["enabled"] shouldBeEqualTo true
    }
}
