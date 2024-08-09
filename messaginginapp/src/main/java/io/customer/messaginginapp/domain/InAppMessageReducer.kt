package io.customer.messaginginapp.domain

import org.reduxkotlin.Reducer

val inAppMessagingReducer: Reducer<InAppMessagingState> = { state, action ->
    when (action) {
        is InAppMessagingAction.Initialize -> state.copy(isInitialized = true)
        is InAppMessagingAction.SetCurrentRoute -> state.copy(currentRoute = action.route)
        is InAppMessagingAction.PollingInterval -> state.copy(pollingInterval = action.interval)
        is InAppMessagingAction.SetUser -> state.copy(currentUser = action.user)
        is InAppMessagingAction.UpdateMessages -> state.copy(messages = action.new)
        is InAppMessagingAction.EngineStartLoading -> state.copy(isLoading = true)
        is InAppMessagingAction.EngineStopLoading -> state.copy(isLoading = false)
        is InAppMessagingAction.ShowModal -> state.copy(isModalVisible = true, currentModalMessage = action.message)
        is InAppMessagingAction.DismissModal -> state.copy(isModalVisible = false, currentModalMessage = null)
        is InAppMessagingAction.ProcessMessage -> state.copy(currentMessageBeingProcessed = action.message)
        is InAppMessagingAction.EmbedMessage -> state.copy(currentMessageBeingProcessed = null, currentModalMessage = null)
        is InAppMessagingAction.ClearUser -> state.copy(currentUser = null, messages = emptyList())
        else -> state // For actions that don't change state
    }
}
