package io.customer.messaginginapp.gist.data.sse

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
 */
internal class HeartbeatTimer(
    private val sseLogger: InAppSseLogger,
    private val scope: CoroutineScope
) {

    private val _timeoutFlow = MutableStateFlow<HeartbeatTimeoutEvent?>(null)
    val timeoutFlow: StateFlow<HeartbeatTimeoutEvent?> = _timeoutFlow.asStateFlow()

    private var currentTimerJob: Job? = null
    private val timerMutex = Mutex()

    /**
     * Start the heartbeat timer with the specified timeout.
     *
     * If a timer is already running, it will be cancelled and replaced
     * with the new timer.
     *
     * @param timeoutMs Timeout in milliseconds after which the timer will expire
     */
    suspend fun startTimer(timeoutMs: Long) {
        timerMutex.withLock {
            sseLogger.logHeartbeatTimerStarting(timeoutMs)

            // Cancel existing timer if running
            currentTimerJob?.cancel()
            // Reset timeout flow when starting new timer
            _timeoutFlow.value = null

            val timerJob = scope.launch {
                try {
                    delay(timeoutMs)
                    sseLogger.logHeartbeatTimerExpired(timeoutMs)
                    timerMutex.withLock {
                        _timeoutFlow.value = HeartbeatTimeoutEvent
                    }
                } catch (_: CancellationException) {
                    sseLogger.logHeartbeatTimerCancelled()
                }
            }
            currentTimerJob = timerJob
        }
    }

    /**
     * Reset the heartbeat timer.
     * Cancels any running timer and clears the timeout flow.
     * Should be called when connection failures occur.
     */
    suspend fun reset() {
        timerMutex.withLock {
            sseLogger.logHeartbeatTimerResetting()
            currentTimerJob?.cancel()
            currentTimerJob = null
            _timeoutFlow.value = null
        }
    }
}

internal object HeartbeatTimeoutEvent
