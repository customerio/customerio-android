package io.customer.messaginginapp.domain

import android.content.Context
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.MessagePosition

sealed class InAppMessagingAction {
    data class Initialize(val siteId: String, val dataCenter: String, val context: Context, val environment: GistEnvironment) : InAppMessagingAction()
    data class SetPollingInterval(val interval: Long) : InAppMessagingAction()
    data class LifecycleAction(val state: LifecycleState) : InAppMessagingAction()
    object Reset : InAppMessagingAction()
    data class SetCurrentRoute(val route: String) : InAppMessagingAction()
    data class CancelMessage(val message: Message) : InAppMessagingAction()
    data class ShowModalMessage(val message: Message, val position: MessagePosition? = null) : InAppMessagingAction()
    data class EmbedMessage(val message: Message, val elementId: String) : InAppMessagingAction()
    data class SetUser(val user: String) : InAppMessagingAction()
    data class ProcessMessages(val messages: List<Message>) : InAppMessagingAction()
    object ClearMessagesInQueue : InAppMessagingAction()
    data class UpdateMessagesToQueue(val messages: List<Message>) : InAppMessagingAction()
    data class ModalMessageShown(val message: Message) : InAppMessagingAction()
    data class DismissMessage(val message: Message) : InAppMessagingAction()
    data class DismissViaAction(val message: Message) : InAppMessagingAction()
    data class Error(val message: String) : InAppMessagingAction()

    // old ones
//    data class PollingInterval(val interval: Long) : InAppMessagingAction()
//    data class SetUser(val user: String) : InAppMessagingAction()
//    data class SetupGistView(val message: Message) : InAppMessagingAction()
//    data class UpdateMessages(val previous: List<Message>, val new: List<Message>) : InAppMessagingAction()
//    object EngineStartLoading : InAppMessagingAction()
//    object EngineStopLoading : InAppMessagingAction()
//    data class ShowModal(val message: Message) : InAppMessagingAction()
//    data class DismissModal(val message: Message) : InAppMessagingAction()
//    data class CancelModal(val message: Message) : InAppMessagingAction()
//    data class OnShowError(val message: Message) : InAppMessagingAction()
//    data class EmbedMessage(val message: Message, val elementId: String) : InAppMessagingAction()
//    data class DismissPersistentMessage(val message: Message) : InAppMessagingAction()
//    data class ProcessMessage(val message: Message, val properties: GistProperties) : InAppMessagingAction()
//    object ClearUser : InAppMessagingAction()
//    data class LogEvent(val event: String) : InAppMessagingAction()
}

enum class LifecycleState {
    Foreground, Background
}
