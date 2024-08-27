package io.customer.messaginginapp.state

import android.content.Context
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.model.Message

data class InAppMessagingState(
    val context: Context? = null,
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
            "context=$context,\n" +
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
    val differences = mutableMapOf<String, Pair<Any?, Any?>>()

    if (this.siteId != other.siteId) {
        differences["siteId"] = Pair(this.siteId, other.siteId)
    }
    if (this.dataCenter != other.dataCenter) {
        differences["dataCenter"] = Pair(this.dataCenter, other.dataCenter)
    }
    if (this.environment != other.environment) {
        differences["environment"] = Pair(this.environment, other.environment)
    }
    if (this.pollInterval != other.pollInterval) {
        differences["pollInterval"] = Pair(this.pollInterval, other.pollInterval)
    }
    if (this.userId != other.userId) {
        differences["userId"] = Pair(this.userId, other.userId)
    }
    if (this.currentRoute != other.currentRoute) {
        differences["currentRoute"] = Pair(this.currentRoute, other.currentRoute)
    }
    if (this.currentMessageState != other.currentMessageState) {
        differences["currentMessageState"] = Pair(this.currentMessageState, other.currentMessageState)
    }
    if (this.messagesInQueue != other.messagesInQueue) {
        differences["messagesInQueue"] = Pair(this.messagesInQueue, other.messagesInQueue)
    }
    if (this.shownMessageQueueIds != other.shownMessageQueueIds) {
        differences["shownMessageQueueIds"] = Pair(this.shownMessageQueueIds, other.shownMessageQueueIds)
    }

    return differences
}
