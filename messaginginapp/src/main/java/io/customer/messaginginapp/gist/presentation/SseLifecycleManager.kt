package io.customer.messaginginapp.gist.presentation

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.customer.messaginginapp.gist.data.sse.InAppSseLogger
import io.customer.messaginginapp.gist.data.sse.SseConnectionManager
import io.customer.messaginginapp.state.InAppMessagingManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the lifecycle-aware SSE connection for in-app messaging.
 */
internal class SseLifecycleManager(
    private val inAppMessagingManager: InAppMessagingManager,
    processLifecycleOwner: LifecycleOwner,
    private val sseConnectionManager: SseConnectionManager,
    private val sseLogger: InAppSseLogger,
    private val mainThreadPoster: MainThreadPoster = HandlerMainThreadPoster()
) {

    private val isForegrounded = AtomicBoolean(false)

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            handleForegrounded()
        }

        override fun onStop(owner: LifecycleOwner) {
            handleBackgrounded()
        }
    }

    init {
        // Lifecycle registration must be on main thread
        mainThreadPoster.post {
            processLifecycleOwner.lifecycle.addObserver(lifecycleObserver)

            if (processLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                handleForegrounded()
            }
        }

        subscribeToSseFlagChanges()
        subscribeToUserIdentificationChanges()
    }

    fun reset() {
        mainThreadPoster.post {
            // If app is foregrounded, restart connection if SSE should be used
            if (isForegrounded.get()) {
                val state = inAppMessagingManager.getCurrentState()
                if (state.shouldUseSse) {
                    sseLogger.logRestartingAfterReset()
                    sseConnectionManager.startConnection()
                }
            }
        }
    }

    private fun handleForegrounded() {
        if (!isForegrounded.compareAndSet(false, true)) {
            return
        }

        val state = inAppMessagingManager.getCurrentState()
        // Only start SSE connection if SSE should be used (enabled AND user identified)
        // Anonymous users should use polling instead of SSE
        if (state.shouldUseSse) {
            sseLogger.logAppForegrounded()
            sseConnectionManager.startConnection()
        } else {
            sseLogger.logAppForegroundedSseNotUsed(state.sseEnabled, state.isUserIdentified)
        }
    }

    private fun handleBackgrounded() {
        if (!isForegrounded.compareAndSet(true, false)) {
            return
        }

        sseLogger.logAppBackgrounded()
        sseConnectionManager.stopConnection()
    }

    private fun subscribeToSseFlagChanges() {
        inAppMessagingManager.subscribeToAttribute({ it.sseEnabled }) { sseEnabled ->
            sseLogger.logSseFlagChanged(sseEnabled)

            if (!isForegrounded.get()) {
                sseLogger.logSseFlagChangedWhileBackgrounded(sseEnabled)
                return@subscribeToAttribute
            }

            val state = inAppMessagingManager.getCurrentState()

            // Only start SSE connection if shouldUseSse (enabled AND user identified)
            if (state.shouldUseSse) {
                sseLogger.logSseEnabledWhileForegrounded()
                sseConnectionManager.startConnection()
            } else if (sseEnabled && !state.isUserIdentified) {
                // SSE enabled but user is anonymous - don't start SSE
                sseLogger.logSseEnabledButUserAnonymous()
            } else if (!sseEnabled) {
                sseLogger.logSseDisabledWhileForegrounded()
                sseConnectionManager.stopConnection()
            }
        }
    }

    /**
     * Subscribe to user identification changes.
     * - When a user becomes identified and SSE is enabled, start SSE connection
     * - When a user becomes anonymous, stop SSE and fall back to polling
     */
    private fun subscribeToUserIdentificationChanges() {
        inAppMessagingManager.subscribeToAttribute({ it.isUserIdentified }) { isIdentified ->
            sseLogger.logUserIdentificationChanged(isIdentified)

            if (!isForegrounded.get()) {
                return@subscribeToAttribute
            }

            val state = inAppMessagingManager.getCurrentState()

            if (state.shouldUseSse) {
                // User became identified and SSE is enabled - start SSE connection
                sseLogger.logEnablingSseForIdentifiedUser()
                sseConnectionManager.startConnection()
            } else if (!isIdentified && state.sseEnabled) {
                // User became anonymous and SSE flag is enabled - stop SSE, fall back to polling
                sseLogger.logDisablingSseForAnonymousUser()
                sseConnectionManager.stopConnection()
            }
        }
    }
}
