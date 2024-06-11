package io.customer.messaginginapp.support

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.gson.JsonParser
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.presentation.GistModalActivity
import io.customer.messaginginapp.support.GistConstants.IN_APP_MESSAGE_DEFERRED_MAX_DELAY
import io.customer.messaginginapp.type.InAppMessage
import io.customer.sdk.extensions.random
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

fun Message.asInAppMessage(): InAppMessage = InAppMessage.getFromGistMessage(gistMessage = this)

fun String.toPageRuleContains(): String = "^(.*$this.*)\$"

fun String.toPageRuleEquals(): String = "^($this)\$"

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
    pageRule: String? = null
): Message = Message(
    messageId = messageId,
    queueId = queueId,
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

val isInAppMessageLoading: Boolean
    get() = getInAppMessageActivity() != null
val isInAppMessageVisible: Boolean
    get() = getInAppMessageActivity()?.isEngineVisible ?: false

fun <T> Deferred<T>.awaitWithTimeoutBlocking(
    timeMillis: Long = IN_APP_MESSAGE_DEFERRED_MAX_DELAY
): T = runBlocking { withTimeout(timeMillis) { await() } }

fun <T> Collection<Deferred<T>>.awaitWithTimeoutBlocking(
    timeMillis: Long = IN_APP_MESSAGE_DEFERRED_MAX_DELAY
): List<T> = runBlocking { withTimeout(timeMillis) { awaitAll(*toTypedArray()) } }
