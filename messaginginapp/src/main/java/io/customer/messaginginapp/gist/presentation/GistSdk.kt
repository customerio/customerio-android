package io.customer.messaginginapp.gist.presentation

import android.app.Application
import androidx.lifecycle.Lifecycle
import io.customer.messaginginapp.di.gistQueue
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.domain.InAppMessagingAction
import io.customer.messaginginapp.domain.LifecycleState
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.store.GlobalPreferenceStore
import java.util.Timer
import kotlin.concurrent.timer
import kotlinx.coroutines.flow.filter

class GistSdk(
    private val application: Application,
    siteId: String,
    dataCenter: String,
    environment: GistEnvironment = GistEnvironment.PROD
) {
    private val inAppMessagingManager = SDKComponent.inAppMessagingManager
    private val state = inAppMessagingManager.getCurrentState()
    private val globalPreferenceStore: GlobalPreferenceStore
        get() = SDKComponent.android().globalPreferenceStore
    private val logger = SDKComponent.logger

    private var timer: Timer? = null
    private val gistQueue = SDKComponent.gistQueue

    private fun resetTimer() {
        timer?.cancel()
        timer = null
    }

    private fun onActivityResumed() {
        inAppMessagingManager.dispatch(InAppMessagingAction.LifecycleAction(state = LifecycleState.Foreground))
//        fetchInAppMessages(state.pollInterval)
    }

    private fun onActivityPaused() {
        inAppMessagingManager.dispatch(InAppMessagingAction.LifecycleAction(state = LifecycleState.Background))
        resetTimer()
    }

    init {
        inAppMessagingManager.dispatch(InAppMessagingAction.Initialize(siteId = siteId, dataCenter = dataCenter, context = application, environment = environment))
        subscribeToLifecycleEvents()
    }

    internal fun reset() {
        inAppMessagingManager.dispatch(InAppMessagingAction.Reset)
        // Remove user token from preferences
        globalPreferenceStore.removeUserId()
        gistQueue.clearPrefs(application)
        resetTimer()
    }

    private fun fetchInAppMessages(duration: Long) {
        logger.debug("Starting polling with duration: $duration")
        timer?.cancel()
        // create a timer to run the task after the initial run
        timer = timer(name = "GistPolling", daemon = true, period = 30000) {
            gistQueue.fetchUserMessages()
        }
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
        logger.debug("Current gist route is already set to: ${state.currentRoute}, new route is: $route")

        inAppMessagingManager.dispatch(InAppMessagingAction.SetCurrentRoute(route))
    }

    fun setUserId(userId: String) {
        if (state.userId == userId) {
            logger.debug("Current user id is already set to: ${state.userId}, ignoring new user id")
            return
        }
        globalPreferenceStore.saveUserId(userId)
        fetchInAppMessages(state.pollInterval)
        inAppMessagingManager.dispatch(InAppMessagingAction.SetUser(userId))
        // Fetch messages for the new user
    }

    fun dismissMessage() {
        inAppMessagingManager.dispatch(InAppMessagingAction.CancelMessage(state.currentMessage ?: return))
    }
}

interface GistListener {
    fun embedMessage(message: Message, elementId: String)
    fun onMessageShown(message: Message)
    fun onMessageDismissed(message: Message)
    fun onMessageCancelled(message: Message)
    fun onError(message: Message)
    fun onAction(message: Message, currentRoute: String, action: String, name: String)
}
