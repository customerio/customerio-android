package io.customer.messaginginapp.state

import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.MessagePosition

sealed class InAppMessagingAction {
    data class Initialize(val siteId: String, val dataCenter: String, val environment: GistEnvironment) : InAppMessagingAction()
    data class SetPollingInterval(val interval: Long) : InAppMessagingAction()
    data class SetPageRoute(val route: String) : InAppMessagingAction()
    data class LoadMessage(val message: Message, val position: MessagePosition? = null) : InAppMessagingAction()
    data class EmbedMessage(val message: Message, val elementId: String) : InAppMessagingAction()
    data class SetUserIdentifier(val user: String) : InAppMessagingAction()
    data class ProcessMessageQueue(val messages: List<Message>) : InAppMessagingAction()
    data class DisplayMessage(val message: Message) : InAppMessagingAction()
    data class DismissMessage(val message: Message, val shouldLog: Boolean = true, val viaCloseAction: Boolean = true) : InAppMessagingAction()
    data class ReportError(val message: String) : InAppMessagingAction()

    sealed class EngineAction {
        data class Tap(val message: Message, val route: String, val name: String, val action: String) : InAppMessagingAction()
        data class MessageLoadingFailed(val message: Message) : InAppMessagingAction()
    }

    object ClearMessageQueue : InAppMessagingAction()
    object Reset : InAppMessagingAction()
}
