package io.customer.android.sample.kotlin_compose.ui.inline

import android.util.Log
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.sdk.core.di.SDKComponent

object InlineUtils {
    const val ELEMENT_ID_STICKY_HEADER = "sticky-header"
    const val ELEMENT_ID_INLINE = "inline"
    const val ELEMENT_ID_BELOW_FOLD = "below-fold"

    fun logMessage(tag: String, message: String) {
        Log.d(tag, "[DEBUG] $message")
    }

    fun getInlineMessages(tag: String): List<Message> {
        val inAppMessagingManager = SDKComponent.inAppMessagingManager
        val state = inAppMessagingManager.getCurrentState()
        val inlineMessages = state.messagesInQueue.filter { it.gistProperties.elementId != null }
        logMessage(tag = tag, message = "Current inline messages: ${inlineMessages.count()}")
        return inlineMessages
    }
}
