package io.customer.messaginginapp.state

import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.model.InboxMessage
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.MessagePosition

internal sealed class InAppMessagingAction {
    data class Initialize(val siteId: String, val dataCenter: String, val environment: GistEnvironment) : InAppMessagingAction()
    data class SetPollingInterval(val interval: Long) : InAppMessagingAction()
    data class SetSseEnabled(val enabled: Boolean) : InAppMessagingAction()
    data class SetPageRoute(val route: String) : InAppMessagingAction()
    data class LoadMessage(val message: Message, val position: MessagePosition? = null) : InAppMessagingAction()
    data class EmbedMessages(val messages: List<Message>) : InAppMessagingAction()
    data class SetUserIdentifier(val user: String) : InAppMessagingAction()
    data class SetAnonymousIdentifier(val anonymousId: String) : InAppMessagingAction()
    data class ProcessMessageQueue(val messages: List<Message>) : InAppMessagingAction()
    data class ProcessInboxMessages(val messages: List<InboxMessage>) : InAppMessagingAction()
    data class DisplayMessage(val message: Message) : InAppMessagingAction()
    data class DismissMessage(val message: Message, val shouldLog: Boolean = true, val viaCloseAction: Boolean = true) : InAppMessagingAction()
    data class ReportError(val message: String) : InAppMessagingAction()

    sealed class EngineAction {
        data class Tap(val message: Message, val route: String, val name: String, val action: String) : InAppMessagingAction()
        data class MessageLoadingFailed(val message: Message) : InAppMessagingAction()
    }

    sealed class InboxAction(open val message: InboxMessage) : InAppMessagingAction() {
        data class UpdateOpened(override val message: InboxMessage, val opened: Boolean) : InboxAction(message)
        data class DeleteMessage(override val message: InboxMessage) : InboxAction(message)
    }

    object ClearMessageQueue : InAppMessagingAction()
    object Reset : InAppMessagingAction()
}

internal fun InAppMessagingAction.shouldMarkMessageAsShown(): Boolean {
    return when (this) {
        is InAppMessagingAction.DisplayMessage -> {
            // Mark the message as shown if it's not persistent
            !message.gistProperties.persistent
        }

        is InAppMessagingAction.DismissMessage -> {
            // Mark the message as shown if it's persistent and should be logged and dismissed via close action only
            message.gistProperties.persistent && shouldLog && viaCloseAction
        }

        else -> false
    }
}
