package io.customer.messaginginapp.testutils.extension

import android.util.Base64
import com.google.gson.JsonParser
import io.customer.commontest.extensions.random
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.type.InAppMessage
import io.customer.messaginginapp.type.getMessage
import java.util.UUID

fun getNewRandomMessage(): Message = InAppMessage(String.random, String.random, String.random).getMessage()

fun mapToInAppMessage(message: Message): InAppMessage = InAppMessage.getFromGistMessage(gistMessage = message)

fun pageRuleContains(route: String): String = "^(.*$route.*)\$"

fun pageRuleEquals(route: String): String = "^($route)\$"

fun decodeOptionsString(options: String): Message {
    val decodedOptions = String(Base64.decode(options, Base64.DEFAULT), Charsets.UTF_8)
    val decodedMessage = JsonParser().parse(decodedOptions).asJsonObject
    return Message(
        messageId = decodedMessage["messageId"].asString,
        instanceId = decodedMessage["instanceId"].asString,
        priority = decodedMessage["priority"]?.asInt,
        queueId = decodedMessage["queueId"]?.asString
    )
}

fun createInAppMessage(
    messageId: String = UUID.randomUUID().toString(),
    campaignId: String? = "test_campaign_id",
    queueId: String? = "test_queue_id",
    position: String? = "center",
    priority: Int? = null,
    pageRule: String? = null
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
            }
        )
    }
)
