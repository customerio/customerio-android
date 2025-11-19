package io.customer.messaginginapp.gist.data.sse

import io.customer.sdk.core.util.Logger
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
 * Manages retry logic for SSE connections.
 *
 * This class handles retry decision-making separately from the main application state.
 * It tracks retry attempts and emits decisions via a flow to the connection manager.
 * All delays and waiting happen inside this helper.
 */
internal class SseRetryHelper(
    private val logger: Logger,
    private val scope: CoroutineScope
) {

    companion object {
        private const val MAX_RETRY_COUNT = 3
        private const val RETRY_DELAY_MS = 5000L
    }

    private val stateMutex = Mutex()
    private var retryCount = 0
    private var retryJob: Job? = null

    private val _retryDecisionFlow = MutableStateFlow<RetryDecision?>(null)
    val retryDecisionFlow: StateFlow<RetryDecision?> = _retryDecisionFlow.asStateFlow()

    /**
     * Schedules a retry for the given error.
     * Emits appropriate retry decision based on error type and retry count.
     */
    suspend fun scheduleRetry(error: SseError) {
        if (error.shouldRetry) {
            attemptRetry()
        } else {
            logger.info("SSE Retry: Non-retryable error - falling back to polling")
            emitRetryDecision(RetryDecision.RetryNotPossible)
        }
    }

    private fun emitRetryDecision(decision: RetryDecision) {
        if (!_retryDecisionFlow.tryEmit(decision)) {
            logger.error("SSE Retry: tryEmit failed")
        }
    }

    private suspend fun attemptRetry() {
        val maxRetriesReached: Boolean
        val newRetryCount: Int

        stateMutex.withLock {
            if (retryCount >= MAX_RETRY_COUNT) {
                maxRetriesReached = true
                newRetryCount = retryCount
            } else {
                maxRetriesReached = false
                retryCount++
                newRetryCount = retryCount
            }
        }

        if (maxRetriesReached) {
            logger.error("SSE Retry: Max retries exceeded ($newRetryCount/$MAX_RETRY_COUNT) - falling back to polling")
            emitRetryDecision(RetryDecision.MaxRetriesReached)
            return
        }

        // First retry - emit immediately (no delay)
        if (newRetryCount == 1) {
            emitRetryDecision(RetryDecision.RetryNow(newRetryCount))
            return
        }

        // Subsequent retries - schedule with delay
        val job = scope.launch {
            delay(RETRY_DELAY_MS)
            emitRetryDecision(RetryDecision.RetryNow(newRetryCount))
        }

        stateMutex.withLock {
            retryJob?.cancel() // Cancel any existing job
            retryJob = job
        }
    }

    /**
     * Reset retry state.
     * Called when a connection is successfully established.
     */
    suspend fun resetRetryState() {
        stateMutex.withLock {
            retryJob?.cancel()
            retryJob = null
            retryCount = 0
        }

        _retryDecisionFlow.value = null
    }
}
