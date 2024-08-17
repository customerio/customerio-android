package io.customer.messaginginapp.domain

import android.content.Context
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.MessagePosition

sealed class InAppMessagingAction {
    data class Initialize(val siteId: String, val dataCenter: String, val context: Context, val environment: GistEnvironment) : InAppMessagingAction()
    data class SetPollingInterval(val interval: Long) : InAppMessagingAction()
    data class LifecycleAction(val state: LifecycleState) : InAppMessagingAction()
    data class SetCurrentRoute(val route: String) : InAppMessagingAction()
    data class ShowModalMessage(val message: Message, val position: MessagePosition? = null) : InAppMessagingAction()
    data class EmbedMessage(val message: Message, val elementId: String) : InAppMessagingAction()
    data class SetUser(val user: String) : InAppMessagingAction()
    data class ProcessMessages(val messages: List<Message>) : InAppMessagingAction()
    data class UpdateMessagesToQueue(val messages: List<Message>) : InAppMessagingAction()
    data class MakeMessageVisible(val message: Message) : InAppMessagingAction()
    data class DismissMessage(val message: Message, val shouldLog: Boolean = true, val viaCloseAction: Boolean = true) : InAppMessagingAction()
    data class Error(val message: String) : InAppMessagingAction()

    object ClearMessagesInQueue : InAppMessagingAction()
    object Reset : InAppMessagingAction()
}

enum class LifecycleState {
    Foreground, Background
}
