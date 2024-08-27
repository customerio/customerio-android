package io.customer.messaginginapp.state

import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.model.Message

data class InAppMessagingState(
    val siteId: String = "",
    val dataCenter: String = "",
    val environment: GistEnvironment = GistEnvironment.PROD,
    val pollInterval: Long = 600_000L,
    val userId: String? = null,
    val currentRoute: String? = null,
    val currentMessageState: MessageState = MessageState.Initial,
    val messagesInQueue: Set<Message> = setOf(),
    val shownMessageQueueIds: Set<String> = setOf()
) {
    override fun toString(): String {
        return "InAppMessagingState(" +
            "siteId='$siteId',\n" +
            "dataCenter='$dataCenter',\n" +
            "environment=$environment,\n" +
            "pollInterval=$pollInterval,\n" +
            "userId=$userId,\n" +
            "currentRoute=$currentRoute,\n" +
            "currentMessageState=$currentMessageState,\n" +
            "messagesInQueue=${messagesInQueue.map(Message::queueId)},\n" +
            "shownMessageQueueIds=$shownMessageQueueIds)"
    }
}

sealed class MessageState {
    object Initial : MessageState()
    data class Loading(val message: Message) : MessageState()
    data class Displayed(val message: Message) : MessageState()
    data class Embedded(val message: Message, val elementId: String) : MessageState()
    data class Dismissed(val message: Message) : MessageState()

    override fun toString(): String {
        return when (this) {
            is Initial -> "Initial"
            is Loading -> "Loading(message=${message.queueId})"
            is Displayed -> "Displayed(message=${message.queueId})"
            is Embedded -> "Embedded(message=${message.queueId}, elementId=$elementId)"
            is Dismissed -> "Dismissed(message=${message.queueId})"
        }
    }
}

fun InAppMessagingState.diff(other: InAppMessagingState): Map<String, Pair<Any?, Any?>> {
    return listOf(
        "siteId" to (siteId to other.siteId),
        "dataCenter" to (dataCenter to other.dataCenter),
        "environment" to (environment to other.environment),
        "pollInterval" to (pollInterval to other.pollInterval),
        "userId" to (userId to other.userId),
        "currentRoute" to (currentRoute to other.currentRoute),
        "currentMessageState" to (currentMessageState to other.currentMessageState),
        "messagesInQueue" to (messagesInQueue to other.messagesInQueue),
        "shownMessageQueueIds" to (shownMessageQueueIds to other.shownMessageQueueIds)
    ).filter { (_, pair) -> pair.first != pair.second }
        .toMap()
}
