package io.customer.messaginginapp.gist.presentation

import android.app.Application
import androidx.lifecycle.Lifecycle
import io.customer.messaginginapp.di.gistQueue
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.domain.InAppMessagingAction
import io.customer.messaginginapp.domain.InAppMessagingState
import io.customer.messaginginapp.domain.MessageState
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
) : GistListener {
    private val inAppMessagingManager = SDKComponent.inAppMessagingManager
    private val state: InAppMessagingState
        get() = inAppMessagingManager.getCurrentState()
    private val globalPreferenceStore: GlobalPreferenceStore
        get() = SDKComponent.android().globalPreferenceStore
    private val logger = SDKComponent.logger

    private var timer: Timer? = null
    private val gistQueue = SDKComponent.gistQueue

    private var listeners: List<GistListener> = emptyList()

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
        inAppMessagingManager.dispatch(InAppMessagingAction.Initialize(siteId = siteId, dataCenter = dataCenter, context = application, environment = environment))
        subscribeToEvents()
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
        timer = timer(name = "GistPolling", daemon = true, period = 20000) {
            gistQueue.fetchUserMessages()
        }
    }

    fun addListener(listener: GistListener) {
        listeners += listener
    }

    private fun subscribeToEvents() {
        inAppMessagingManager.setListener(this)

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

        inAppMessagingManager.subscribeToAttribute({ it.pollInterval }) { interval ->
            fetchInAppMessages(interval)
        }
    }

    fun setCurrentRoute(route: String) {
        logger.debug("Current gist route is: ${state.currentRoute}, new route is: $route")

        inAppMessagingManager.dispatch(InAppMessagingAction.NavigateToRoute(route))
    }

    fun setUserId(userId: String) {
        if (state.userId == userId) {
            logger.debug("Current user id is already set to: ${state.userId}, ignoring new user id")
            return
        }
        globalPreferenceStore.saveUserId(userId)
        inAppMessagingManager.dispatch(InAppMessagingAction.SetUserIdentifier(userId))
        fetchInAppMessages(state.pollInterval)
    }

    fun dismissMessage() {
        val currentMessageState = state.currentMessageState as? MessageState.Loaded
        inAppMessagingManager.dispatch(InAppMessagingAction.DismissMessage(message = currentMessageState?.message ?: return))
    }

    override fun embedMessage(message: Message, elementId: String) {
        listeners.forEach { it.embedMessage(message, elementId) }
    }

    override fun onMessageShown(message: Message) {
        listeners.forEach { it.onMessageShown(message) }
    }

    override fun onMessageDismissed(message: Message) {
        listeners.forEach { it.onMessageDismissed(message) }
    }

    override fun onMessageCancelled(message: Message) {
        listeners.forEach { it.onMessageCancelled(message) }
    }

    override fun onError(message: Message) {
        listeners.forEach { it.onError(message) }
    }

    override fun onAction(message: Message, currentRoute: String, action: String, name: String) {
        listeners.forEach { it.onAction(message, currentRoute, action, name) }
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
