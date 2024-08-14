package io.customer.messaginginapp.domain

import org.reduxkotlin.Reducer

val inAppMessagingReducer: Reducer<InAppMessagingState> = { state, action ->
    when (action) {
        is InAppMessagingAction.Initialize -> state.copy(isInitialized = true)
        is InAppMessagingAction.LifecycleAction -> state.copy(isAppInForeground = action.state == LifecycleState.Foreground)
        is InAppMessagingAction.Reset -> InAppMessagingState()
        is InAppMessagingAction.SetCurrentRoute -> state.copy(currentRoute = action.route)
        is InAppMessagingAction.CancelCurrentMessage -> state.copy(currentMessage = null)
        else -> state
    }
}
