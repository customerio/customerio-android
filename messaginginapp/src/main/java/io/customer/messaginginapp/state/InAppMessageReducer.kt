package io.customer.messaginginapp.state

import io.customer.sdk.core.di.SDKComponent
import org.reduxkotlin.Reducer

val inAppMessagingReducer: Reducer<InAppMessagingState> = { state, action ->
    val newState = when (action) {
        is InAppMessagingAction.Initialize -> state.copy(siteId = action.siteId, dataCenter = action.dataCenter, environment = action.environment)
        is InAppMessagingAction.SetPageRoute -> state.copy(currentRoute = action.route)
        is InAppMessagingAction.SetUserIdentifier -> state.copy(userId = action.user)
        is InAppMessagingAction.ClearMessageQueue -> state.copy(messagesInQueue = setOf())

        is InAppMessagingAction.ProcessMessageQueue -> state.copy(messagesInQueue = action.messages.toSet())
        is InAppMessagingAction.SetPollingInterval -> state.copy(pollInterval = action.interval)
        is InAppMessagingAction.DismissMessage -> state.copy(currentMessageState = MessageState.Dismissed(action.message))
        is InAppMessagingAction.EngineAction.MessageLoadingFailed -> state.copy(currentMessageState = MessageState.Dismissed(action.message))
        is InAppMessagingAction.LoadMessage -> state.copy(currentMessageState = MessageState.Loading(action.message))
        is InAppMessagingAction.Reset -> InAppMessagingState(siteId = state.siteId, dataCenter = state.dataCenter, environment = state.environment)
        is InAppMessagingAction.DisplayMessage -> {
            action.message.queueId?.let { queueId ->
                state.copy(
                    currentMessageState = MessageState.Displayed(action.message),
                    shownMessageQueueIds = state.shownMessageQueueIds + queueId,
                    messagesInQueue = state.messagesInQueue.filterNot { it.queueId == queueId }.toSet()
                )
            } ?: state
        }

        else -> state
    }
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
