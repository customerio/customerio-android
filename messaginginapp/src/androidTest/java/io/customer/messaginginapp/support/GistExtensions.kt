package io.customer.messaginginapp.support

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.gson.JsonParser
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.presentation.GistModalActivity
import io.customer.messaginginapp.type.InAppMessage
import io.customer.sdk.extensions.random

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
    messageId: String = String.random,
    campaignId: String? = String.random,
    queueId: String? = String.random,
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
            }
        )
    }
)

fun getInAppMessageActivity(): GistModalActivity? {
    var runningActivity: GistModalActivity? = null
    val activityMonitor = ActivityLifecycleMonitorRegistry.getInstance()

    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val activities = activityMonitor.getActivitiesInStage(Stage.RESUMED)
        for (activity in activities) {
            if (activity is GistModalActivity) {
                runningActivity = activity
                break
            }
        }
    }

    return runningActivity
}
