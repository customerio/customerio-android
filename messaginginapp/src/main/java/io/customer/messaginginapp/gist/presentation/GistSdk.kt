package io.customer.messaginginapp.gist.presentation

import androidx.lifecycle.Lifecycle
import io.customer.messaginginapp.di.gistQueue
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.di.inAppPreferenceStore
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.state.MessageState
import io.customer.messaginginapp.store.InAppPreferenceStore
import io.customer.sdk.core.di.SDKComponent
import java.util.Timer
import kotlin.concurrent.timer
import kotlinx.coroutines.flow.filter

interface GistProvider {
    fun setCurrentRoute(route: String)
    fun setUserId(userId: String)
    fun dismissMessage()
    fun reset()
}

class GistSdk(
    siteId: String,
    dataCenter: String,
    environment: GistEnvironment = GistEnvironment.PROD
) : GistProvider {
    private val inAppMessagingManager = SDKComponent.inAppMessagingManager
    private val state: InAppMessagingState
        get() = inAppMessagingManager.getCurrentState()
    private val logger = SDKComponent.logger
    private val inAppPreferenceStore: InAppPreferenceStore
        get() = SDKComponent.inAppPreferenceStore

    private var timer: Timer? = null
    private val gistQueue = SDKComponent.gistQueue

    private fun resetTimer() {
        timer?.cancel()
        timer = null
    }

    private fun onActivityResumed() {
        logger.debug("Activity resumed, starting polling")
        fetchInAppMessages(state.pollInterval)
    }

    private fun onActivityPaused() {
        logger.debug("Activity paused, stopping polling")
        resetTimer()
    }

    init {
        inAppMessagingManager.dispatch(InAppMessagingAction.Initialize(siteId = siteId, dataCenter = dataCenter, environment = environment))
        subscribeToEvents()
    }

    override fun reset() {
        inAppMessagingManager.dispatch(InAppMessagingAction.Reset)
        // Remove user token from preferences
        inAppPreferenceStore.clearAll()
        resetTimer()
    }

    private fun fetchInAppMessages(duration: Long, initialDelay: Long = 0) {
        logger.debug("Starting polling with duration: $duration and initial delay: $initialDelay")
        timer?.cancel()
        // create a timer to run the task after the initial run
        timer = timer(name = "GistPolling", daemon = true, initialDelay = initialDelay, period = duration) {
            gistQueue.fetchUserMessages()
        }
    }

    private fun subscribeToEvents() {
        SDKComponent.activityLifecycleCallbacks.subscribe { events ->
            events
                .filter { state ->
                    state.event == Lifecycle.Event.ON_RESUME || state.event == Lifecycle.Event.ON_PAUSE
                }
                .filter { state ->
                    // ignore events from GistModalActivity to prevent polling/stopping polling when the in-app is displayed
                    state.activity.get() != null && state.activity.get() !is GistModalActivity
                }
                .collect { state ->
                    when (state.event) {
                        Lifecycle.Event.ON_RESUME -> onActivityResumed()
                        Lifecycle.Event.ON_PAUSE -> onActivityPaused()
                        else -> {}
                    }
                }
        }

        inAppMessagingManager.subscribeToAttribute({ it.pollInterval }) { interval ->
            fetchInAppMessages(duration = interval, initialDelay = interval)
        }
    }

    override fun setCurrentRoute(route: String) {
        logger.debug("Current gist route is: ${state.currentRoute}, new route is: $route")

        if (state.currentRoute == route) return

        inAppMessagingManager.dispatch(InAppMessagingAction.SetPageRoute(route))
    }

    override fun setUserId(userId: String) {
        if (state.userId == userId) {
            logger.debug("Current user id is already set to: ${state.userId}, ignoring new user id")
            return
        }
        inAppMessagingManager.dispatch(InAppMessagingAction.SetUserIdentifier(userId))
        fetchInAppMessages(state.pollInterval)
    }

    override fun dismissMessage() {
        // only dismiss the message if it is currently displayed
        val currentMessageState = state.currentMessageState as? MessageState.Displayed ?: return
        inAppMessagingManager.dispatch(InAppMessagingAction.DismissMessage(message = currentMessageState.message))
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
