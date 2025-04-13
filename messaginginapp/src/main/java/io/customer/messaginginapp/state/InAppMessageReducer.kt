package io.customer.messaginginapp.state

import io.customer.sdk.core.di.SDKComponent
import org.reduxkotlin.Reducer

val inAppMessagingReducer: Reducer<InAppMessagingState> = { state, action ->
    val newState = when (action) {
        // Simple state copy operations
        is InAppMessagingAction.Initialize ->
            state.copy(siteId = action.siteId, dataCenter = action.dataCenter, environment = action.environment)

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

        is InAppMessagingAction.EngineAction.MessageLoadingFailed ->
            state.copy(modalMessageState = MessageState.Dismissed(action.message))

        is InAppMessagingAction.LoadMessage ->
            state.copy(modalMessageState = MessageState.Loading(action.message))

        // Using the reset method for cleaner implementation
        is InAppMessagingAction.Reset -> state.reset()

        is InAppMessagingAction.EmbedMessages -> {
            // Handling embedding messages in a single loop for better performance
            val newEmbeddedMessagesState = action.messages.fold(state.embeddedMessagesState) { acc, message ->
                message.elementId?.let { elementId ->
                    acc.addMessage(message, elementId)
                } ?: acc
            }
            state.copy(embeddedMessagesState = newEmbeddedMessagesState)
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
                    val elementId = action.message.elementId ?: ""
                    state.updateEmbeddedMessage(
                        queueId = queueId,
                        newState = InlineMessageState.Embedded(action.message, elementId),
                        shownMessageQueueIds = shownMessageQueueIds,
                        messagesInQueue = filteredQueue
                    )
                } else {
                    // Update modal message state
                    state.copy(
                        modalMessageState = MessageState.Displayed(action.message),
                        shownMessageQueueIds = shownMessageQueueIds,
                        messagesInQueue = filteredQueue
                    )
                }
            } ?: state
        }

        is InAppMessagingAction.DismissMessage -> {
            var shownMessageQueueIds = state.shownMessageQueueIds
            // If the message should be tracked shown when it is dismissed, add the queueId to shownMessageQueueIds.
            if (action.shouldMarkMessageAsShown() && action.message.queueId != null) {
                shownMessageQueueIds = shownMessageQueueIds + action.message.queueId
            }

            if (action.message.isEmbedded) {
                // For embedded messages
                if (action.message.queueId != null) {
                    // Update embedded message state if it has a queueId
                    state.updateEmbeddedMessage(
                        queueId = action.message.queueId,
                        newState = InlineMessageState.Dismissed(action.message),
                        shownMessageQueueIds = shownMessageQueueIds
                    )
                } else {
                    // For embedded messages without queueId
                    // Just return the state unchanged
                    state
                }
            } else {
                // Handle modal message
                state.copy(
                    modalMessageState = MessageState.Dismissed(action.message),
                    shownMessageQueueIds = shownMessageQueueIds
                )
            }
        }

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
        changes.forEach { (property, values) ->
            SDKComponent.logger.debug("  $property: ${values.first} -> ${values.second}")
        }
    } else {
        SDKComponent.logger.debug("Store: no state changes after action")
    }

    newState
}
