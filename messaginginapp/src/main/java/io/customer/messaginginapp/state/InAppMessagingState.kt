package io.customer.messaginginapp.state

import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.model.Message

internal data class InAppMessagingState(
    val siteId: String = "",
    val dataCenter: String = "",
    val environment: GistEnvironment = GistEnvironment.PROD,
    val pollInterval: Long = 600_000L,
    val userId: String? = null,
    val anonymousId: String? = null,
    val currentRoute: String? = null,
    val sessionId: String = "",
    val modalMessageState: ModalMessageState = ModalMessageState.Initial,
    val queuedInlineMessagesState: QueuedInlineMessagesState = QueuedInlineMessagesState(),
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
        append("sessionId='$sessionId',\n")
        append("modalMessageState=$modalMessageState,\n")
        append("embeddedMessagesState=$queuedInlineMessagesState,\n")
        append("messagesInQueue=${messagesInQueue.map(Message::queueId)},\n")
        append("shownMessageQueueIds=$shownMessageQueueIds)")
    }

    fun diff(other: InAppMessagingState): Map<String, Pair<Any?, Any?>> {
        return buildMap {
            if (siteId != other.siteId) put("siteId", siteId to other.siteId)
            if (dataCenter != other.dataCenter) put("dataCenter", dataCenter to other.dataCenter)
            if (environment != other.environment) put("environment", environment to other.environment)
            if (pollInterval != other.pollInterval) put("pollInterval", pollInterval to other.pollInterval)
            if (userId != other.userId) put("userId", userId to other.userId)
            if (anonymousId != other.anonymousId) put("anonymousId", anonymousId to other.anonymousId)
            if (currentRoute != other.currentRoute) put("currentRoute", currentRoute to other.currentRoute)
            if (sessionId != other.sessionId) put("sessionId", sessionId to other.sessionId)
            if (modalMessageState != other.modalMessageState) put("modalMessageState", modalMessageState to other.modalMessageState)
            if (queuedInlineMessagesState != other.queuedInlineMessagesState) put("embeddedMessagesState", queuedInlineMessagesState to other.queuedInlineMessagesState)
            if (messagesInQueue != other.messagesInQueue) put("messagesInQueue", messagesInQueue to other.messagesInQueue)
            if (shownMessageQueueIds != other.shownMessageQueueIds) put("shownMessageQueueIds", shownMessageQueueIds to other.shownMessageQueueIds)
        }
    }
}

internal sealed class InlineMessageState {
    abstract val message: Message

    data class ReadyToEmbed(override val message: Message, val elementId: String) : InlineMessageState() {
        override fun toString() = "ReadyToEmbed(message=${message.queueId}, elementId=$elementId)"
    }

    data class Embedded(override val message: Message, val elementId: String) : InlineMessageState() {
        override fun toString() = "Embedded(message=${message.queueId}, elementId=$elementId)"
    }

    data class Dismissed(override val message: Message) : InlineMessageState() {
        override fun toString() = "Dismissed(message=${message.queueId})"
    }
}

internal sealed class ModalMessageState {
    object Initial : ModalMessageState() {
        override fun toString() = "Initial"
    }

    data class Loading(val message: Message) : ModalMessageState() {
        override fun toString() = "Loading(message=${message.queueId})"
    }

    data class Displayed(val message: Message) : ModalMessageState() {
        override fun toString() = "Displayed(message=${message.queueId})"
    }

    data class Dismissed(val message: Message) : ModalMessageState() {
        override fun toString() = "Dismissed(message=${message.queueId})"
    }
}

internal data class QueuedInlineMessagesState(
    internal val messagesByElementId: Map<String, InlineMessageState> = emptyMap()
) {
    fun addMessage(message: Message, elementId: String): QueuedInlineMessagesState {
        val state = InlineMessageState.ReadyToEmbed(message, elementId)
        val updatedMap = buildMap(messagesByElementId.size + 1) {
            putAll(messagesByElementId)
            put(elementId, state)
        }
        return copy(messagesByElementId = updatedMap)
    }

    fun updateMessageState(queueId: String, newState: InlineMessageState): QueuedInlineMessagesState {
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

    override fun toString() = buildString {
        append("EmbeddedMessagesState(")
        append("messages=${messagesByElementId.size}, ")
        append("ids=${messagesByElementId.keys}, ")
        append("states=${messagesByElementId.values}")
        append(")")
    }
}
