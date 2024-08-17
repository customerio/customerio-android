package io.customer.messaginginapp.domain

import org.reduxkotlin.Reducer

val inAppMessagingReducer: Reducer<InAppMessagingState> = { state, action ->
    when (action) {
        is InAppMessagingAction.Initialize -> state.copy(siteId = action.siteId, dataCenter = action.dataCenter, context = action.context, environment = action.environment)
        is InAppMessagingAction.LifecycleAction -> state.copy(isAppInForeground = action.state == LifecycleState.Foreground)
        is InAppMessagingAction.SetCurrentRoute -> state.copy(currentRoute = action.route)
        is InAppMessagingAction.SetUser -> state.copy(userId = action.user)
        is InAppMessagingAction.ClearMessagesInQueue -> state.copy(messagesInQueue = setOf())
        is InAppMessagingAction.UpdateMessagesToQueue -> {
            val messagesToStore = action.messages.filter {
                state.messagesInQueue.find { localMessage -> localMessage.queueId == it.queueId } == null
            }
            state.copy(messagesInQueue = messagesToStore.toSet(), currentMessageState = MessageState.Default)
        }

        is InAppMessagingAction.DismissMessage -> state.copy(currentMessageState = MessageState.Dismissed(action.message))
        is InAppMessagingAction.ShowModalMessage -> state.copy(currentMessageState = MessageState.Default)
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
