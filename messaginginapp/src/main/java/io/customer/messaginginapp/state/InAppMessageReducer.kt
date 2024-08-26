package io.customer.messaginginapp.state

import org.reduxkotlin.Reducer

val inAppMessagingReducer: Reducer<InAppMessagingState> = { state, action ->
    when (action) {
        is InAppMessagingAction.Initialize -> state.copy(siteId = action.siteId, dataCenter = action.dataCenter, environment = action.environment)
        is InAppMessagingAction.SetPageRoute -> state.copy(currentRoute = action.route)
        is InAppMessagingAction.SetUserIdentifier -> state.copy(userId = action.user)
        is InAppMessagingAction.ClearMessageQueue -> state.copy(messagesInQueue = setOf())

        is InAppMessagingAction.ProcessMessageQueue -> state.copy(messagesInQueue = action.messages.toSet())
        is InAppMessagingAction.SetPollingInterval -> state.copy(pollInterval = action.interval)
        is InAppMessagingAction.DismissMessage -> state.copy(currentMessageState = MessageState.Dismissed(action.message))
        is InAppMessagingAction.ProcessMessage -> state.copy(currentMessageState = MessageState.Processing(action.message))
        is InAppMessagingAction.Reset -> InAppMessagingState(siteId = state.siteId, dataCenter = state.dataCenter, environment = state.environment, pollInterval = state.pollInterval)
        is InAppMessagingAction.DisplayMessage -> {
            action.message.queueId?.let { queueId ->
                state.copy(
                    currentMessageState = MessageState.Loaded(action.message),
                    shownMessageQueueIds = state.shownMessageQueueIds + queueId,
                    messagesInQueue = state.messagesInQueue.filterNot { it.queueId == queueId }.toSet()
                )
            } ?: state
        }

        else -> state
    }
}
