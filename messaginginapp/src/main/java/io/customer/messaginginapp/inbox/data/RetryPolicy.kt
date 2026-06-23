package io.customer.messaginginapp.inbox.data

import kotlinx.coroutines.delay

/**
 * Exponential-backoff retry configuration for the templates/branding fetch path.
 *
 * Each attempt is bounded by the dedicated client's per-attempt 5s timeout
 * ([InboxApi.INBOX_CALL_TIMEOUT_SECONDS]); this policy governs how many attempts
 * are made and how long we wait between them.
 */
internal data class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMillis: Long = 500L,
    val multiplier: Double = 2.0
) {
    fun delayForAttempt(attemptIndexZeroBased: Int): Long {
        // attempt 0 -> base, attempt 1 -> base*mult, attempt 2 -> base*mult^2 ...
        var d = baseDelayMillis.toDouble()
        repeat(attemptIndexZeroBased) { d *= multiplier }
        return d.toLong()
    }
}

/**
 * Runs [block] with exponential backoff. Returns the value on first success.
 * On the final failure it rethrows the last error so the caller can fold it into
 * the terminal [InboxFetchOutcome].
 *
 * [delayFn] is injectable so tests can assert backoff sequencing without sleeping.
 *
 * [onAttempt] is invoked once per failed attempt for logging/observability: the
 * first arg is the zero-based attempt index, the second is the backoff delay (ms)
 * about to be waited before the NEXT attempt, or null on the final (no-retry) attempt.
 */
internal suspend fun <T> retryWithBackoff(
    policy: RetryPolicy,
    delayFn: suspend (Long) -> Unit = { delay(it) },
    onAttempt: (attemptIndexZeroBased: Int, retryDelayMillis: Long?) -> Unit = { _, _ -> },
    block: suspend () -> T
): T {
    var lastError: Throwable? = null
    for (attempt in 0 until policy.maxAttempts) {
        try {
            return block()
        } catch (e: Throwable) {
            lastError = e
            val isLastAttempt = attempt == policy.maxAttempts - 1
            if (!isLastAttempt) {
                val delayMillis = policy.delayForAttempt(attempt)
                onAttempt(attempt, delayMillis)
                delayFn(delayMillis)
            } else {
                onAttempt(attempt, null)
            }
        }
    }
    throw lastError ?: InboxFetchException("retry exhausted with no captured error")
}
