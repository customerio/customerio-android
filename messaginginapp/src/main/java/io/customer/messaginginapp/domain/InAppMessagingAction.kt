package io.customer.messaginginapp.domain

import android.content.Context
import io.customer.messaginginapp.gist.data.model.GistProperties
import io.customer.messaginginapp.gist.data.model.Message

sealed class InAppMessagingAction {
    data class Initialize(val siteId: String, val dataCenter: String) : InAppMessagingAction()
    data class LifecycleAction(val state: LifecycleState) : InAppMessagingAction()
    data class Reset(val context: Context) : InAppMessagingAction()
    data class SetCurrentRoute(val route: String) : InAppMessagingAction()
    data class CancelCurrentMessage(val message: Message) : InAppMessagingAction()

    // old ones
    data class PollingInterval(val interval: Long) : InAppMessagingAction()
    data class SetUser(val user: String) : InAppMessagingAction()
    data class SetupGistView(val message: Message) : InAppMessagingAction()
    data class UpdateMessages(val previous: List<Message>, val new: List<Message>) : InAppMessagingAction()
    object EngineStartLoading : InAppMessagingAction()
    object EngineStopLoading : InAppMessagingAction()
    data class ShowModal(val message: Message) : InAppMessagingAction()
    data class DismissModal(val message: Message) : InAppMessagingAction()
    data class CancelModal(val message: Message) : InAppMessagingAction()
    data class OnShowError(val message: Message) : InAppMessagingAction()
    data class EmbedMessage(val message: Message, val elementId: String) : InAppMessagingAction()
    data class DismissPersistentMessage(val message: Message) : InAppMessagingAction()
    data class ProcessMessage(val message: Message, val properties: GistProperties) : InAppMessagingAction()
    object ClearUser : InAppMessagingAction()
    data class LogEvent(val event: String) : InAppMessagingAction()
}

enum class LifecycleState {
    Foreground, Background
}
