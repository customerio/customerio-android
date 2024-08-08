package io.customer.messaginginapp.domain

import io.customer.messaginginapp.gist.data.model.GistProperties
import io.customer.messaginginapp.gist.data.model.Message

sealed class InAppMessagingEvent {
    data class Initialized(val timestamp: Long) : InAppMessagingEvent()
    data class PollingIntervalUpdated(val interval: Long) : InAppMessagingEvent()
    data class RouteChanged(val from: String, val to: String) : InAppMessagingEvent()
    data class UserSet(val user: String) : InAppMessagingEvent()
    data class MessagesUpdated(val previous: List<Message>, val new: List<Message>) : InAppMessagingEvent()
    data class ViewSetup(val message: Message) : InAppMessagingEvent()
    object EngineLoadingStarted : InAppMessagingEvent()
    object EngineLoadingStopped : InAppMessagingEvent()
    data class ModalShown(val message: Message) : InAppMessagingEvent()
    data class MessageProcessing(val message: Message, val properties: GistProperties) : InAppMessagingEvent()
    data class MessagedEmbedded(val message: Message, val elementId: String) : InAppMessagingEvent()
    object ModalDismissed : InAppMessagingEvent()
    object PersistentMessageDismissed : InAppMessagingEvent()
    object UserCleared : InAppMessagingEvent()
    data class StateLogged(val state: InAppMessagingState) : InAppMessagingEvent()
    data class EventLog(val event: String) : InAppMessagingEvent()
}
