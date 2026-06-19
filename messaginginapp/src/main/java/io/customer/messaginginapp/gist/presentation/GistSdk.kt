package io.customer.messaginginapp.gist.presentation

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.customer.messaginginapp.di.gistQueue
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.di.inAppPreferenceStore
import io.customer.messaginginapp.di.sseLifecycleManager
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.state.ModalMessageState
import io.customer.messaginginapp.store.InAppPreferenceStore
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.HandlerMainThreadPoster
import io.customer.sdk.core.util.MainThreadPoster
import java.util.Timer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.timer

internal interface GistProvider {
    fun setCurrentRoute(route: String)
    fun setUserId(userId: String)
    fun setAnonymousId(anonymousId: String)
    fun dismissMessage()
    fun reset()
    fun fetchInAppMessages()
}

internal class GistSdk(
    siteId: String,
    dataCenter: String,
    environment: GistEnvironment = GistEnvironment.PROD,
    // Injected for testability; mirrors SseLifecycleManager so polling and SSE share the same
    // process-level lifecycle source.
    private val processLifecycleOwner: LifecycleOwner = ProcessLifecycleOwner.get(),
    private val mainThreadPoster: MainThreadPoster = HandlerMainThreadPoster()
) : GistProvider {
    private val inAppMessagingManager = SDKComponent.inAppMessagingManager
    private val state: InAppMessagingState
        get() = inAppMessagingManager.getCurrentState()
    private val logger = SDKComponent.logger
    private val inAppPreferenceStore: InAppPreferenceStore
        get() = SDKComponent.inAppPreferenceStore

    private var timer: Timer? = null
    private val gistQueue = SDKComponent.gistQueue
    private val sseLifecycleManager = SDKComponent.sseLifecycleManager

    // Tracks process foreground state. Polling is scoped to the *process* lifecycle (matching
    // SseLifecycleManager), not to individual activities, so a single polling timer survives
    // activity navigation and the display/dismissal of our own GistModalActivity.
    private val isForegrounded = AtomicBoolean(false)

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            onAppForegrounded()
        }

        override fun onStop(owner: LifecycleOwner) {
            onAppBackgrounded()
        }
    }

    private fun resetTimer() {
        timer?.cancel()
        timer = null
    }

    private fun onAppForegrounded() {
        if (!isForegrounded.compareAndSet(false, true)) {
            logger.debug("[Polling] App foreground event ignored - already foregrounded")
            return
        }

        val currentState = state
        logger.debug("[Polling] App foregrounded (shouldUseSse=${currentState.shouldUseSse}, sseEnabled=${currentState.sseEnabled}, isUserIdentified=${currentState.isUserIdentified})")
        if (currentState.shouldUseSse) {
            // SSE is active; SseLifecycleManager owns fetching/connection while foregrounded.
            logger.debug("[Polling] Not starting polling on foreground - SSE is active")
            return
        }
        // Start polling with an immediate catch-up fetch for messages received while backgrounded.
        fetchInAppMessages(duration = currentState.pollInterval)
    }

    private fun onAppBackgrounded() {
        if (!isForegrounded.compareAndSet(true, false)) {
            logger.debug("[Polling] App background event ignored - already backgrounded")
            return
        }
        logger.debug("[Polling] App backgrounded - stopping polling")
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
        sseLifecycleManager.reset()
    }

    override fun fetchInAppMessages() {
        fetchInAppMessages(duration = state.pollInterval)
    }

    private fun fetchInAppMessages(duration: Long, initialDelay: Long = 0) {
        val currentState = state
        // Only skip polling if SSE should be used (both flag enabled AND user identified)
        if (currentState.shouldUseSse) {
            logger.debug("GistSdk skipping polling - SSE is active (sseEnabled=${currentState.sseEnabled}, isUserIdentified=${currentState.isUserIdentified})")
            return
        }

        logger.debug("[Polling] Starting polling (sseEnabled=${currentState.sseEnabled}, isUserIdentified=${currentState.isUserIdentified}, interval=${duration}ms, initialDelay=${initialDelay}ms)")
        timer?.cancel()
        // create a timer to run the task after the initial run
        timer = timer(name = "GistPolling", daemon = true, initialDelay = initialDelay, period = duration) {
            logger.debug("[Polling] Poll tick - fetching user messages")
            gistQueue.fetchUserMessages()
        }
    }

    private fun subscribeToEvents() {
        // Scope polling to the *process* foreground lifecycle (foreground/background) rather than
        // individual activity resume/pause. This keeps a single polling timer alive across
        // activity navigation and while our own GistModalActivity is shown, and removes the
        // immediate refetch that previously fired whenever the host activity resumed after a
        // modal closed (the source of the tight retry loop when a modal failed to load).
        // Mirrors SseLifecycleManager. Lifecycle registration must happen on the main thread.
        mainThreadPoster.post {
            processLifecycleOwner.lifecycle.addObserver(lifecycleObserver)
            if (processLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                onAppForegrounded()
            }
        }

        inAppMessagingManager.subscribeToAttribute({ it.pollInterval }) { interval ->
            // Only manage polling when app is foregrounded
            if (!isForegrounded.get()) {
                return@subscribeToAttribute
            }

            val currentState = state
            if (currentState.shouldUseSse) {
                return@subscribeToAttribute
            }
            logger.debug("[Polling] Poll interval changed to ${interval}ms - restarting polling")
            fetchInAppMessages(duration = interval, initialDelay = interval)
        }

        // Keep the poll timer in sync with SSE availability while foregrounded: stop polling when
        // SSE becomes active, resume polling when it is no longer active (e.g. SSE flag disabled
        // or the user becomes anonymous).
        inAppMessagingManager.subscribeToAttribute({ it.sseEnabled }) { _ ->
            updatePollingForSseAvailability(reason = "SSE flag changed")
        }

        inAppMessagingManager.subscribeToAttribute({ it.isUserIdentified }) { _ ->
            updatePollingForSseAvailability(reason = "user identification changed")
        }
    }

    private fun updatePollingForSseAvailability(reason: String) {
        // Only manage polling when app is foregrounded
        if (!isForegrounded.get()) {
            return
        }

        val currentState = state
        if (currentState.shouldUseSse) {
            logger.debug("[Polling] $reason - SSE now active, stopping polling")
            resetTimer()
        } else {
            logger.debug("[Polling] $reason - SSE not active, ensuring polling is running")
            fetchInAppMessages(duration = currentState.pollInterval)
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
        // Note: fetch is now controlled by the event handler, not here
    }

    override fun setAnonymousId(anonymousId: String) {
        if (state.anonymousId == anonymousId) {
            logger.debug("Current anonymous id is already set to: ${state.anonymousId}, ignoring new anonymous id")
            return
        }
        logger.debug("Setting anonymous id to: $anonymousId")
        inAppMessagingManager.dispatch(InAppMessagingAction.SetAnonymousIdentifier(anonymousId))
        // Note: fetch is now controlled by the event handler, not here
    }

    override fun dismissMessage() {
        // only dismiss the message if it is currently displayed
        val currentModalMessageState = state.modalMessageState as? ModalMessageState.Displayed ?: return
        inAppMessagingManager.dispatch(InAppMessagingAction.DismissMessage(message = currentModalMessageState.message))
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
