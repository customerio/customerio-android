package io.customer.messaginginapp.domain

import org.reduxkotlin.Reducer

val inAppMessagingReducer: Reducer<InAppMessagingState> = { state, action ->
    when (action) {
        is InAppMessagingAction.Initialize -> state.copy(siteId = action.siteId, dataCenter = action.dataCenter, context = action.context, environment = action.environment)
        is InAppMessagingAction.LifecycleAction -> state.copy(isAppInForeground = action.state == LifecycleState.Foreground)
        is InAppMessagingAction.SetCurrentRoute -> state.copy(currentRoute = action.route)
        is InAppMessagingAction.CancelMessage -> state.copy(currentMessage = null)
        is InAppMessagingAction.ShowModalMessage -> state.copy(currentMessage = action.message)
        is InAppMessagingAction.SetUser -> state.copy(userId = action.user)
        is InAppMessagingAction.ClearMessagesInQueue -> state.copy(messagesInQueue = setOf())
        is InAppMessagingAction.UpdateMessagesToQueue -> {
            val messagesToStore = action.messages.filter {
                state.messagesInQueue.find { localMessage -> localMessage.queueId == it.queueId } == null
            }
            state.copy(messagesInQueue = messagesToStore.toSet())
        }

        is InAppMessagingAction.ModalMessageShown -> {
            action.message.queueId?.let {
                state.copy(
                    shownMessageQueueIds = state.shownMessageQueueIds + action.message.queueId,
                    messagesInQueue = state.messagesInQueue.filter { message -> message.queueId != action.message.queueId }.toSet()
                )
            } ?: state
        }

        is InAppMessagingAction.DismissMessage -> {
            if (state.currentMessage?.queueId == action.message.queueId) {
                state.copy(currentMessage = null)
            } else {
                state
            }
        }

        is InAppMessagingAction.DismissViaAction -> {
            if (state.currentMessage?.queueId == action.message.queueId) {
                state.copy(currentMessage = null)
            } else {
                state
            }
        }

        is InAppMessagingAction.Reset -> InAppMessagingState()

        else -> state
    }
}
