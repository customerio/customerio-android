package io.customer.messaginginapp.testutils.extension

import io.customer.commontest.extensions.random
import io.customer.messaginginapp.gist.data.model.InboxMessage
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.response.InboxMessageResponse
import io.customer.messaginginapp.gist.data.model.response.toDomain
import io.customer.messaginginapp.type.InAppMessage
import io.customer.messaginginapp.type.getMessage
import io.customer.messaginginapp.ui.controller.InAppMessageViewController
import java.util.Date
import java.util.UUID

// Date helper functions for testing
internal fun dateNow(): Date = Date()
internal fun dateHoursAgo(hours: Int): Date = Date(Date().time - hours * 3600_000L)
internal fun dateDaysAgo(days: Int): Date = Date(Date().time - days * 86400_000L)

fun getNewRandomMessage(): Message = InAppMessage(String.random, String.random, String.random).getMessage()

fun mapToInAppMessage(message: Message): InAppMessage = InAppMessage.getFromGistMessage(gistMessage = message)

fun pageRuleContains(route: String): String = "^(.*$route.*)\$"

fun pageRuleEquals(route: String): String = "^($route)\$"

fun createInAppMessage(
    messageId: String = UUID.randomUUID().toString(),
    campaignId: String? = "test_campaign_id",
    queueId: String? = "test_queue_id",
    position: String? = "center",
    priority: Int? = null,
    pageRule: String? = null,
    persistent: Boolean? = null,
    elementId: String? = null
): Message = Message(
    messageId = messageId,
    queueId = queueId,
    priority = priority,
    properties = buildMap {
        put(
            "gist",
            buildMap {
                campaignId?.let { value -> put("campaignId", value) }
                pageRule?.let { value -> put("routeRuleAndroid", value) }
                position?.let { value -> put("position", value) }
                persistent?.let { value -> put("persistent", value) }
                elementId?.let { value -> put("elementId", value) }
            }
        )
    }
)

// Creates an InboxMessage using InboxMessageResponse to leverage the toDomain() mapping logic
fun createInboxMessage(
    queueId: String = UUID.randomUUID().toString(),
    deliveryId: String? = UUID.randomUUID().toString(),
    expiry: Date? = null,
    sentAt: Date = Date(),
    topics: List<String> = emptyList(),
    type: String? = null,
    opened: Boolean? = null,
    priority: Int? = null,
    properties: Map<String, Any?>? = null
): InboxMessage = InboxMessageResponse(
    queueId = queueId,
    deliveryId = deliveryId,
    expiry = expiry,
    sentAt = sentAt,
    topics = topics,
    type = type,
    opened = opened,
    priority = priority,
    properties = properties
).toDomain()

fun createGistAction(action: String): String = "gist://$action"

internal fun InAppMessageViewController<*>.setMessageAndRouteForTest(message: Message, route: String) {
    this.currentMessage = message
    this.currentRoute = route
}
