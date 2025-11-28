package io.customer.messaginginapp.gist.data.sse

/**
 * Represents decisions made by SseRetryHelper about retry behavior.
 * This is emitted to the connection manager via a flow.
 * All delays are handled inside SseRetryHelper, so only RetryNow or MaxRetriesReached are emitted.
 */
internal sealed class RetryDecision {

    /**
     * Retry now (all delays have been handled by SseRetryHelper)
     * @param attemptCount The current retry attempt number (1-based)
     */
    data class RetryNow(val attemptCount: Int) : RetryDecision()

    /**
     * Maximum retries reached, fallback to polling
     */
    object MaxRetriesReached : RetryDecision()

    object RetryNotPossible : RetryDecision()
}
