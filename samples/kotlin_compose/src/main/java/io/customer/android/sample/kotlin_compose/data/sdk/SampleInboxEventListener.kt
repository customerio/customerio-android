package io.customer.android.sample.kotlin_compose.data.sdk

import io.customer.android.sample.kotlin_compose.util.Logger
import io.customer.messaginginapp.type.InboxActionMessage
import io.customer.messaginginapp.type.InboxEventListener

/**
 * Sample implementation for `InboxEventListener`.
 *
 * Observational: it logs each callback and returns `false` from [messageActionTaken] so the SDK
 * still applies its default action handling (e.g. opening an http(s) url). Return `true` instead to
 * fully handle the action and suppress that default.
 */
class SampleInboxEventListener(private val logger: Logger = Logger()) : InboxEventListener {

    override fun messageActionTaken(
        message: InboxActionMessage,
        actionName: String,
        actionValue: String
    ): Boolean {
        logEvent("messageActionTaken. name: $actionName, value: $actionValue, message: ${message.messageId}")
        return false
    }

    override fun messageShown(message: InboxActionMessage) {
        logEvent("messageShown. message: ${message.messageId}")
    }

    override fun messageOpened(message: InboxActionMessage) {
        logEvent("messageOpened. message: ${message.messageId}")
    }

    override fun messageDismissed(message: InboxActionMessage) {
        logEvent("messageDismissed. message: ${message.messageId}")
    }

    private fun logEvent(message: String) {
        logger.v("[CIO-Inbox] sample listener: $message")
    }
}
