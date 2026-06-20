package io.customer.messaginginapp.gist.presentation

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.customer.messaginginapp.gist.data.listeners.GistQueue
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.sdk.core.util.HandlerMainThreadPoster
import io.customer.sdk.core.util.Logger
import io.customer.sdk.core.util.MainThreadPoster
import java.util.Timer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.timer

/**
 * Manages lifecycle-aware in-app message polling.
 *
 * Polling is scoped to the *process* foreground lifecycle, not individual activities, mirroring
 * [SseLifecycleManager]. A single polling timer survives activity navigation and the display of
 * our own [GistModalActivity], so dismissing a modal (normally or after a load failure) never
 * triggers an immediate refetch. Polling stays disabled while SSE is active.
 */
internal class PollingLifecycleManager(
    private val inAppMessagingManager: InAppMessagingManager,
    processLifecycleOwner: LifecycleOwner,
    private val gistQueue: GistQueue,
    private val logger: Logger,
    private val mainThreadPoster: MainThreadPoster = HandlerMainThreadPoster()
) {
    private val isForegrounded = AtomicBoolean(false)

    // @Volatile for cross-thread visibility: written on the main thread (foreground/background) and
    // read from the attribute-subscription coroutines that guard against redundant restarts.
    @Volatile
    private var timer: Timer? = null

    // Guards the poll-interval subscription's initial replay emission. The initial/foreground start
    // is owned by handleForegrounded()/fetchInAppMessages(); reacting to the replay would race with
    // and could cancel that catch-up fetch. Only genuine interval changes should restart polling.
    private val pollIntervalInitialized = AtomicBoolean(false)

    private val state: InAppMessagingState
        get() = inAppMessagingManager.getCurrentState()

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            handleForegrounded()
        }

        override fun onStop(owner: LifecycleOwner) {
            handleBackgrounded()
        }
    }

    init {
        // Lifecycle registration must happen on the main thread.
        mainThreadPoster.post {
            processLifecycleOwner.lifecycle.addObserver(lifecycleObserver)
            if (processLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                handleForegrounded()
            }
        }

        subscribeToPollIntervalChanges()
        subscribeToSseFlagChanges()
        subscribeToUserIdentificationChanges()
    }

    /**
     * Triggers an immediate message fetch and (re)starts polling unless SSE is active.
     * Called by external events such as user identification and message dismissal.
     */
    fun fetchInAppMessages() {
        startPolling(duration = state.pollInterval)
    }

    fun reset() {
        resetTimer()
    }

    private fun handleForegrounded() {
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
        startPolling(duration = currentState.pollInterval)
    }

    private fun handleBackgrounded() {
        if (!isForegrounded.compareAndSet(true, false)) {
            logger.debug("[Polling] App background event ignored - already backgrounded")
            return
        }
        logger.debug("[Polling] App backgrounded - stopping polling")
        resetTimer()
    }

    private fun startPolling(duration: Long, initialDelay: Long = 0) {
        val currentState = state
        // Only skip polling if SSE should be used (both flag enabled AND user identified)
        if (currentState.shouldUseSse) {
            logger.debug("[Polling] Skipping polling - SSE is active (sseEnabled=${currentState.sseEnabled}, isUserIdentified=${currentState.isUserIdentified})")
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

    private fun resetTimer() {
        timer?.cancel()
        timer = null
    }

    private fun subscribeToPollIntervalChanges() {
        inAppMessagingManager.subscribeToAttribute({ it.pollInterval }) { interval ->
            // Skip the initial replay emission; the initial start is owned by
            // handleForegrounded()/fetchInAppMessages(). Only react to genuine interval changes.
            if (pollIntervalInitialized.compareAndSet(false, true)) {
                return@subscribeToAttribute
            }

            // Only manage polling when app is foregrounded
            if (!isForegrounded.get()) {
                return@subscribeToAttribute
            }

            val currentState = state
            if (currentState.shouldUseSse) {
                return@subscribeToAttribute
            }
            logger.debug("[Polling] Poll interval changed to ${interval}ms - restarting polling")
            startPolling(duration = interval, initialDelay = interval)
        }
    }

    // Keep the poll timer in sync with SSE availability while foregrounded: stop polling when SSE
    // becomes active, resume polling when it is no longer active (SSE flag disabled or user
    // becomes anonymous).
    private fun subscribeToSseFlagChanges() {
        inAppMessagingManager.subscribeToAttribute({ it.sseEnabled }) { _ ->
            updatePollingForSseAvailability(reason = "SSE flag changed")
        }
    }

    private fun subscribeToUserIdentificationChanges() {
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
        } else if (timer == null) {
            // Resume polling only when it isn't already running, i.e. we're transitioning away
            // from SSE (which had stopped the timer). When polling is already active this branch
            // would otherwise cause a redundant restart + duplicate fetch on every identification
            // or SSE-flag flip that leaves shouldUseSse false (e.g. anon->identified with SSE off,
            // where ModuleMessagingInApp already fetches, or an sseEnabled flip while anonymous).
            logger.debug("[Polling] $reason - SSE not active and polling stopped, starting polling")
            startPolling(duration = currentState.pollInterval)
        } else {
            logger.debug("[Polling] $reason - SSE not active and polling already running, no action")
        }
    }
}
