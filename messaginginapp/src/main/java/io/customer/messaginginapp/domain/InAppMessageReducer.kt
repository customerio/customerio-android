package io.customer.messaginginapp.domain

import org.reduxkotlin.Reducer

val inAppMessagingReducer: Reducer<InAppMessagingState> = { state, action ->
    when (action) {
        is InAppMessagingAction.Initialize -> state.copy(siteId = action.siteId, dataCenter = action.dataCenter, context = action.context, environment = action.environment)
        is InAppMessagingAction.SetCurrentRoute -> state.copy(currentRoute = action.route)
        is InAppMessagingAction.SetUser -> state.copy(userId = action.user)
        is InAppMessagingAction.ClearMessagesInQueue -> state.copy(messagesInQueue = setOf())
        is InAppMessagingAction.LoadMessage -> {
            state.copy(currentMessageState = MessageState.Processing(action.message))
        }

        is InAppMessagingAction.ProcessMessages -> state.copy(messagesInQueue = action.messages.toSet())
        is InAppMessagingAction.SetPollingInterval -> state.copy(pollInterval = action.interval)
        is InAppMessagingAction.DismissMessage -> state.copy(currentMessageState = MessageState.Dismissed(action.message))
        is InAppMessagingAction.ShowModalMessage -> state.copy(currentMessageState = MessageState.Processing(action.message))
        is InAppMessagingAction.Reset -> InAppMessagingState()
        is InAppMessagingAction.MakeMessageVisible -> {
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
