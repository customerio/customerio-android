package io.customer.android.sample.kotlin_compose.data.sdk

import io.customer.android.sample.kotlin_compose.util.Logger
import io.customer.messaginginapp.type.InAppEventListener
import io.customer.messaginginapp.type.InAppMessage
import io.customer.sdk.CustomerIO

/**
 * Sample implementation for `InAppEventListener`.
 */
class InAppMessageEventListener(private val logger: Logger = Logger()) : InAppEventListener {

    override fun messageShown(message: InAppMessage) {
        logInAppEvent("in-app message: messageShown. message: $message")
        trackInAppEvent("messageShown", message)
    }

    override fun messageDismissed(message: InAppMessage) {
        logInAppEvent("in-app message: messageDismissed. message: $message")
        trackInAppEvent("messageDismissed", message)
    }

    override fun errorWithMessage(message: InAppMessage) {
        logInAppEvent("in-app message: errorWithMessage. message: $message")
        trackInAppEvent("errorWithMessage", message)
    }

    override fun messageActionTaken(
        message: InAppMessage,
        actionValue: String,
        actionName: String
    ) {
        logInAppEvent("in-app message: messageActionTaken. action: $actionValue, name: $actionName, message: $message")
        trackInAppEvent(
            "messageActionTaken",
            message,
            hashMapOf("action-value" to actionValue, "action-name" to actionName)
        )
    }

    private fun trackInAppEvent(eventName: String, message: InAppMessage) {
        trackInAppEvent(eventName, message, null)
    }

    private fun logInAppEvent(message: String) {
        logger.v(message)
    }

    private fun trackInAppEvent(
        eventName: String,
        message: InAppMessage,
        arguments: Map<String, String>?
    ) {
        CustomerIO.instance().track(
            "in-app message action",
            HashMap<String, String>().apply {
                arguments?.let { putAll(it) }
                put("event-name", eventName)
                put("message-id", message.messageId)
                val deliveryId = message.deliveryId
                put("delivery-id", deliveryId ?: "NULL")
            }
        )
    }
}
