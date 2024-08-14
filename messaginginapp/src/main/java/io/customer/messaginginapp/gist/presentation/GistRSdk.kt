package io.customer.messaginginapp.gist.presentation

import android.app.Application
import androidx.lifecycle.Lifecycle
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.domain.InAppMessagingAction
import io.customer.messaginginapp.domain.LifecycleState
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.store.GlobalPreferenceStore
import kotlinx.coroutines.flow.filter

class GistRSdk private constructor(
    private val application: Application,
    siteId: String,
    dataCenter: String,
    private val environment: GistEnvironment = GistEnvironment.PROD
) {
    private val inAppMessagingManager = SDKComponent.inAppMessagingManager
    private val state = inAppMessagingManager.getCurrentState()
    private val globalPreferenceStore: GlobalPreferenceStore
        get() = SDKComponent.android().globalPreferenceStore
    private val logger = SDKComponent.logger

    private fun onActivityResumed() {
        inAppMessagingManager.dispatch(InAppMessagingAction.LifecycleAction(state = LifecycleState.Foreground))
    }

    private fun onActivityPaused() {
        inAppMessagingManager.dispatch(InAppMessagingAction.LifecycleAction(state = LifecycleState.Background))
    }

    init {
        inAppMessagingManager.dispatch(InAppMessagingAction.Initialize(siteId = siteId, dataCenter = dataCenter))
        subscribeToLifecycleEvents()
    }

    /**
     * Reset the Gist SDK to its initial state.
     * This method is used for testing purposes only.
     */
    internal fun reset() {
        inAppMessagingManager.dispatch(InAppMessagingAction.Reset(application))
        // Remove user token from preferences
        globalPreferenceStore.removeUserId()
    }

    private fun subscribeToLifecycleEvents() {
        SDKComponent.activityLifecycleCallbacks.subscribe { events ->
            events
                .filter { state ->
                    state.event == Lifecycle.Event.ON_RESUME || state.event == Lifecycle.Event.ON_PAUSE
                }.collect { state ->
                    state.activity.get() ?: return@collect
                    when (state.event) {
                        Lifecycle.Event.ON_RESUME -> onActivityResumed()
                        Lifecycle.Event.ON_PAUSE -> onActivityPaused()
                        else -> {}
                    }
                }
        }
    }

    fun setCurrentRoute(route: String) {
        if (state.currentRoute == route) {
            logger.debug("Current gist route is already set to: ${state.currentRoute}, ignoring new route")
            return
        }
        inAppMessagingManager.dispatch(InAppMessagingAction.SetCurrentRoute(route))
//        gistQueue.fetchUserMessagesFromLocalStore()
    }

//
}
