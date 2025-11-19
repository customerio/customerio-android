package io.customer.messaginginapp.gist.presentation

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.customer.messaginginapp.gist.data.sse.SseConnectionManager
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.sdk.core.util.Logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the lifecycle-aware SSE connection for in-app messaging.
 */
internal class SseLifecycleManager(
    private val inAppMessagingManager: InAppMessagingManager,
    processLifecycleOwner: LifecycleOwner,
    private val sseConnectionManager: SseConnectionManager,
    private val logger: Logger,
    mainThreadPoster: MainThreadPoster = HandlerMainThreadPoster()
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
    }

    fun reset() {
        // If app is foregrounded, restart connection if SSE is enabled
        if (isForegrounded.get()) {
            val state = inAppMessagingManager.getCurrentState()
            if (state.sseEnabled) {
                logger.info("SSE Lifecycle: App still foregrounded after reset, restarting SSE connection")
                sseConnectionManager.startConnection()
            }
        }
    }

    private fun handleForegrounded() {
        if (!isForegrounded.compareAndSet(false, true)) {
            return
        }

        val state = inAppMessagingManager.getCurrentState()
        if (state.sseEnabled) {
            logger.info("SSE Lifecycle: App foregrounded with SSE enabled, starting SSE connection")
            sseConnectionManager.startConnection()
        }
    }

    private fun handleBackgrounded() {
        if (!isForegrounded.compareAndSet(true, false)) {
            return
        }

        logger.info("SSE Lifecycle: App backgrounded, stopping SSE connection")
        sseConnectionManager.stopConnection()
    }

    private fun subscribeToSseFlagChanges() {
        inAppMessagingManager.subscribeToAttribute({ it.sseEnabled }) { sseEnabled ->
            logger.info("SSE Lifecycle: SSE flag changed to: $sseEnabled")

            if (!isForegrounded.get()) {
                return@subscribeToAttribute
            }

            if (sseEnabled) {
                logger.info("SSE Lifecycle: SSE enabled while foregrounded, starting SSE connection")
                sseConnectionManager.startConnection()
            } else {
                logger.info("SSE Lifecycle: SSE disabled while foregrounded, stopping SSE connection")
                sseConnectionManager.stopConnection()
            }
        }
    }
}
