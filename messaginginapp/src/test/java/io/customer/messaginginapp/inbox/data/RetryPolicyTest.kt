package io.customer.messaginginapp.inbox.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RetryPolicyTest {

    @Test
    fun delayForAttempt_givenExponentialPolicy_expectBaseTimesMultiplier() {
        val policy = RetryPolicy(maxAttempts = 4, baseDelayMillis = 100L, multiplier = 2.0)

        policy.delayForAttempt(0) shouldBeEqualTo 100L
        policy.delayForAttempt(1) shouldBeEqualTo 200L
        policy.delayForAttempt(2) shouldBeEqualTo 400L
        policy.delayForAttempt(3) shouldBeEqualTo 800L
    }

    @Test
    fun retryWithBackoff_givenSuccessFirstTry_expectNoDelaysAndResult() = runTest {
        val policy = RetryPolicy(maxAttempts = 3, baseDelayMillis = 100L, multiplier = 2.0)
        val recordedDelays = mutableListOf<Long>()
        var calls = 0

        val result = retryWithBackoff(policy, delayFn = { recordedDelays.add(it) }) {
            calls++
            "ok"
        }

        result shouldBeEqualTo "ok"
        calls shouldBeEqualTo 1
        recordedDelays shouldBeEqualTo emptyList()
    }

    @Test
    fun retryWithBackoff_givenFailuresThenSuccess_expectBackoffBetweenAttempts() = runTest {
        val policy = RetryPolicy(maxAttempts = 3, baseDelayMillis = 100L, multiplier = 2.0)
        val recordedDelays = mutableListOf<Long>()
        var calls = 0

        val result = retryWithBackoff(policy, delayFn = { recordedDelays.add(it) }) {
            calls++
            if (calls < 3) throw InboxFetchException("transient $calls")
            "recovered"
        }

        result shouldBeEqualTo "recovered"
        calls shouldBeEqualTo 3
        // Delays only between attempts (after attempt 0 and attempt 1), not after the success.
        recordedDelays shouldBeEqualTo listOf(100L, 200L)
    }

    @Test
    fun retryWithBackoff_givenAlwaysFails_expectExhaustedAndRethrows() = runTest {
        val policy = RetryPolicy(maxAttempts = 3, baseDelayMillis = 10L, multiplier = 2.0)
        val recordedDelays = mutableListOf<Long>()
        var calls = 0

        val thrown = runCatching {
            retryWithBackoff<String>(policy, delayFn = { recordedDelays.add(it) }) {
                calls++
                throw InboxFetchException("always fails $calls")
            }
        }.exceptionOrNull()

        calls shouldBeEqualTo 3
        // No delay after the final (failing) attempt.
        recordedDelays shouldBeEqualTo listOf(10L, 20L)
        thrown shouldBeInstanceOf InboxFetchException::class
    }
}
