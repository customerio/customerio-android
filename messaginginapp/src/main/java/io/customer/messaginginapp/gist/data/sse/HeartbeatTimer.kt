package io.customer.messaginginapp.gist.data.sse

import io.customer.sdk.core.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Heartbeat timer that monitors server heartbeats and emits timeout events
 * when the server stops sending heartbeats within the expected timeframe.
 *
 * This implementation uses coroutines with proper cancellation handling
 * to ensure no side effects when timers are cancelled or stopped.
 *
 * Thread Safety: All timer operations are protected by a mutex to ensure
 * thread-safe state management.
 */
internal class HeartbeatTimer(
    private val logger: Logger,
    private val scope: CoroutineScope
) {

    private val _timeoutFlow = MutableStateFlow<HeartbeatTimerEvent?>(null)
    val timeoutFlow: StateFlow<HeartbeatTimerEvent?> = _timeoutFlow.asStateFlow()

    private var currentTimerJob: Job? = null
    private val timerMutex = Mutex()

    /**
     * Start the heartbeat timer with the specified timeout.
     *
     * If a timer is already running, it will be cancelled and replaced
     * with the new timer. This method handles both starting and resetting.
     *
     * @param timeoutMs Timeout in milliseconds after which the timer will expire
     */
    suspend fun startTimer(timeoutMs: Long) {
        timerMutex.withLock {
            logger.debug("HeartbeatTimer: Starting timer with ${timeoutMs}ms timeout")

            // Cancel existing timer if running
            currentTimerJob?.cancel()

            // Reset timeout flow when starting new timer
            _timeoutFlow.value = null

            // Start new timer
            val timerJob = scope.launch {
                try {
                    delay(timeoutMs)
                    logger.error("HeartbeatTimer: Timer expired after ${timeoutMs}ms")
                    // Only set timeout if this is still the current timer
                    timerMutex.withLock {
                        if (currentTimerJob == this@launch) {
                            _timeoutFlow.value = HeartbeatTimerEvent.Timeout
                        }
                    }
                } catch (e: CancellationException) {
                    logger.debug("HeartbeatTimer: Timer cancelled gracefully")
                    // Don't emit timeout event on cancellation
                    throw e
                }
            }
            currentTimerJob = timerJob
        }
    }
}
