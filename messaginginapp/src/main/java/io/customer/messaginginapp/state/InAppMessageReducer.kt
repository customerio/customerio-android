package io.customer.messaginginapp.state

import io.customer.sdk.core.di.SDKComponent
import java.util.UUID
import org.reduxkotlin.Reducer

internal val inAppMessagingReducer: Reducer<InAppMessagingState> = { state, action ->
    val newState = when (action) {
        is InAppMessagingAction.Initialize ->
            state.copy(
                siteId = action.siteId,
                dataCenter = action.dataCenter,
                environment = action.environment,
                sessionId = UUID.randomUUID().toString()
            )

        is InAppMessagingAction.SetPageRoute ->
            state.copy(currentRoute = action.route)

        is InAppMessagingAction.SetUserIdentifier ->
            state.copy(userId = action.user)

        is InAppMessagingAction.ClearMessageQueue ->
            state.copy(messagesInQueue = emptySet())

        is InAppMessagingAction.ProcessMessageQueue ->
            state.copy(messagesInQueue = action.messages.toSet())

        is InAppMessagingAction.SetPollingInterval ->
            state.copy(pollInterval = action.interval)

        is InAppMessagingAction.EngineAction.MessageLoadingFailed -> state.withMessageDismissed(
            message = action.message,
            shouldMarkAsShown = false
        )

        is InAppMessagingAction.LoadMessage ->
            state.copy(modalMessageState = ModalMessageState.Loading(action.message))

        is InAppMessagingAction.Reset -> state.copy(
            userId = null,
            currentRoute = null,
            sessionId = UUID.randomUUID().toString(),
            modalMessageState = ModalMessageState.Initial,
            queuedInlineMessagesState = QueuedInlineMessagesState(),
            messagesInQueue = emptySet(),
            shownMessageQueueIds = emptySet()
        )

        is InAppMessagingAction.EmbedMessages -> {
            // Handling embedding messages in a single loop for better performance
            val newEmbeddedMessagesState = action.messages.fold(state.queuedInlineMessagesState) { accState, message ->
                message.embeddedElementId?.let { elementId ->
                    accState.addMessage(message, elementId)
                } ?: accState
            }
            state.copy(queuedInlineMessagesState = newEmbeddedMessagesState)
        }

        is InAppMessagingAction.DisplayMessage -> {
            action.message.queueId?.let { queueId ->
                // If the message should be tracked shown when it is displayed, add the queueId to shownMessageQueueIds.
                val shownMessageQueueIds = if (action.shouldMarkMessageAsShown()) {
                    state.shownMessageQueueIds + queueId
                } else {
                    state.shownMessageQueueIds
                }

                // Remove the message from the queue
                val filteredQueue = state.messagesInQueue.filterNot { it.queueId == queueId }.toSet()

                if (action.message.isEmbedded) {
                    // Update embedded message state
                    val elementId = action.message.embeddedElementId ?: ""
                    state.withUpdatedEmbeddedMessage(
                        queueId = queueId,
                        newState = InlineMessageState.Embedded(action.message, elementId),
                        shownMessageQueueIds = shownMessageQueueIds,
                        messagesInQueue = filteredQueue
                    )
                } else {
                    // Update modal message state
                    state.copy(
                        modalMessageState = ModalMessageState.Displayed(action.message),
                        shownMessageQueueIds = shownMessageQueueIds,
                        messagesInQueue = filteredQueue
                    )
                }
            } ?: state
        }

        is InAppMessagingAction.DismissMessage -> state.withMessageDismissed(
            message = action.message,
            shouldMarkAsShown = action.shouldMarkMessageAsShown()
        )

        is InAppMessagingAction.EngineAction.Tap ->
            state // No state changes for tap action

        is InAppMessagingAction.ReportError ->
            state // No state changes for error reporting

        else -> {
            // Log unexpected action for debugging
            SDKComponent.logger.debug("Unhandled action received: $action")
            state
        }
    }

    // Log state changes
    val changes = state.diff(newState)
    if (changes.isNotEmpty()) {
        SDKComponent.logger.debug("Store: state changes after action:")
        for ((property, values) in changes) {
            SDKComponent.logger.debug("  $property: ${values.first} -> ${values.second}")
        }
    } else {
        SDKComponent.logger.debug("Store: no state changes after action")
    }

    newState
}
