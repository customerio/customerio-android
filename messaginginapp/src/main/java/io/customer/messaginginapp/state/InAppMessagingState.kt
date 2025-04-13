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
    val modalMessageState: MessageState = MessageState.Initial,
    val embeddedMessagesState: EmbeddedMessagesState = EmbeddedMessagesState(),
    val messagesInQueue: Set<Message> = emptySet(),
    val shownMessageQueueIds: Set<String> = emptySet()
) {
    override fun toString(): String = buildString {
        append("InAppMessagingState(")
        append("siteId='$siteId',\n")
        append("dataCenter='$dataCenter',\n")
        append("environment=$environment,\n")
        append("pollInterval=$pollInterval,\n")
        append("userId=$userId,\n")
        append("currentRoute=$currentRoute,\n")
        append("modalMessageState=$modalMessageState,\n")
        append("embeddedMessagesState=$embeddedMessagesState,\n")
        append("messagesInQueue=${messagesInQueue.map(Message::queueId)},\n")
        append("shownMessageQueueIds=$shownMessageQueueIds)")
    }

    // Helper function to create new state with cleared messages but preserve site settings
    fun reset(): InAppMessagingState = copy(
        userId = null,
        currentRoute = null,
        modalMessageState = MessageState.Initial,
        embeddedMessagesState = EmbeddedMessagesState(),
        messagesInQueue = emptySet(),
        shownMessageQueueIds = emptySet()
    )

    // Compute differences between states - moved from extension function to method
    fun diff(other: InAppMessagingState): Map<String, Pair<Any?, Any?>> {
        return buildMap {
            if (siteId != other.siteId) put("siteId", siteId to other.siteId)
            if (dataCenter != other.dataCenter) put("dataCenter", dataCenter to other.dataCenter)
            if (environment != other.environment) put("environment", environment to other.environment)
            if (pollInterval != other.pollInterval) put("pollInterval", pollInterval to other.pollInterval)
            if (userId != other.userId) put("userId", userId to other.userId)
            if (currentRoute != other.currentRoute) put("currentRoute", currentRoute to other.currentRoute)
            if (modalMessageState != other.modalMessageState) put("modalMessageState", modalMessageState to other.modalMessageState)
            if (embeddedMessagesState != other.embeddedMessagesState) put("embeddedMessagesState", embeddedMessagesState to other.embeddedMessagesState)
            if (messagesInQueue != other.messagesInQueue) put("messagesInQueue", messagesInQueue to other.messagesInQueue)
            if (shownMessageQueueIds != other.shownMessageQueueIds) put("shownMessageQueueIds", shownMessageQueueIds to other.shownMessageQueueIds)
        }
    }

    fun updateEmbeddedMessage(
        queueId: String,
        newState: InlineMessageState,
        shownMessageQueueIds: Set<String> = this.shownMessageQueueIds,
        messagesInQueue: Set<Message> = this.messagesInQueue
    ): InAppMessagingState {
        val updatedEmbeddedMessagesState = embeddedMessagesState.updateMessageState(queueId, newState)
        return copy(
            embeddedMessagesState = updatedEmbeddedMessagesState,
            shownMessageQueueIds = shownMessageQueueIds,
            messagesInQueue = messagesInQueue
        )
    }
}

sealed class InlineMessageState {
    abstract val message: Message

    data class ReadyToEmbed(override val message: Message, val elementId: String) : InlineMessageState()
    data class Embedded(override val message: Message, val elementId: String) : InlineMessageState()
    data class Dismissed(override val message: Message) : InlineMessageState()

    override fun toString(): String = when (this) {
        is ReadyToEmbed -> "ReadyToEmbed(message=${message.queueId}, elementId=$elementId)"
        is Embedded -> "Embedded(message=${message.queueId}, elementId=$elementId)"
        is Dismissed -> "Dismissed(message=${message.queueId})"
    }
}

sealed class MessageState {
    object Initial : MessageState()
    data class Loading(val message: Message) : MessageState()
    data class Displayed(val message: Message) : MessageState()
    data class Dismissed(val message: Message) : MessageState()

    // More concise toString with 'when' expression
    override fun toString(): String = when (this) {
        is Initial -> "Initial"
        is Loading -> "Loading(message=${message.queueId})"
        is Displayed -> "Displayed(message=${message.queueId})"
        is Dismissed -> "Dismissed(message=${message.queueId})"
    }
}

data class EmbeddedMessagesState(
    internal val messagesByElementId: Map<String, InlineMessageState> = emptyMap()
) {
    fun addMessage(message: Message, elementId: String): EmbeddedMessagesState {
        val state = InlineMessageState.ReadyToEmbed(message, elementId)
        val updatedMap = buildMap(messagesByElementId.size + 1) {
            putAll(messagesByElementId)
            put(elementId, state)
        }
        return copy(messagesByElementId = updatedMap)
    }

    fun updateMessageState(queueId: String, newState: InlineMessageState): EmbeddedMessagesState {
        val entry = messagesByElementId.entries.find { (_, state) ->
            state.message.queueId == queueId
        } ?: return this

        return copy(
            messagesByElementId = buildMap(messagesByElementId.size) {
                putAll(messagesByElementId)
                put(entry.key, newState)
            }
        )
    }

    fun getMessage(elementId: String): InlineMessageState? = messagesByElementId[elementId]

    fun allMessages(): List<InlineMessageState> = messagesByElementId.values.toList()

    override fun toString(): String =
        "EmbeddedMessagesState(messages=${messagesByElementId.size}, ids=${messagesByElementId.keys})"
}
