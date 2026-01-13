package io.customer.messaginginapp.gist.data.sse

import io.customer.messaginginapp.testutils.core.JUnitTest
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SseRetryHelperTest : JUnitTest() {

    private val sseLogger = mockk<InAppSseLogger>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var retryHelper: SseRetryHelper

    @BeforeEach
    fun setup() {
        retryHelper = SseRetryHelper(sseLogger, testScope)
    }

    @Test
    fun testScheduleRetry_whenRetryableError_thenEmitsRetryNow() = runTest(testDispatcher) {
        // Given
        val retryableError = SseError.NetworkError(java.io.IOException("Network error"))

        // When
        retryHelper.scheduleRetry(retryableError)
        advanceUntilIdle()

        // Then
        val decision = retryHelper.retryDecisionFlow.value
        decision.shouldBeInstanceOf<RetryDecision.RetryNow>()
        (decision as RetryDecision.RetryNow).attemptCount.shouldBeEqualTo(1)
    }

    @Test
    fun testScheduleRetry_whenNonRetryableError_thenEmitsRetryNotPossible() = runTest(testDispatcher) {
        // Given
        val nonRetryableError = SseError.ServerError(
            throwable = Exception("Bad request"),
            responseCode = 400,
            shouldRetry = false
        )

        // When
        retryHelper.scheduleRetry(nonRetryableError)
        advanceUntilIdle()

        // Then
        val decision = retryHelper.retryDecisionFlow.value
        decision.shouldBeInstanceOf<RetryDecision.RetryNotPossible>()
    }

    @Test
    fun testScheduleRetry_whenFirstRetry_thenEmitsImmediately() = runTest(testDispatcher) {
        // Given
        val retryableError = SseError.NetworkError(java.io.IOException("Network error"))

        // When
        retryHelper.scheduleRetry(retryableError)
        advanceUntilIdle()

        // Then - First retry should be immediate (no delay)
        val decision = retryHelper.retryDecisionFlow.value
        decision.shouldBeInstanceOf<RetryDecision.RetryNow>()
        (decision as RetryDecision.RetryNow).attemptCount.shouldBeEqualTo(1)
    }

    @Test
    fun testScheduleRetry_whenMaxRetriesReached_thenEmitsMaxRetriesReached() = runTest(testDispatcher) {
        // Given
        val retryableError = SseError.NetworkError(java.io.IOException("Network error"))

        // When - Retry 4 times (max is 3)
        retryHelper.scheduleRetry(retryableError) // Attempt 1
        advanceUntilIdle()

        retryHelper.scheduleRetry(retryableError) // Attempt 2
        advanceTimeBy(5000L)
        advanceUntilIdle()

        retryHelper.scheduleRetry(retryableError) // Attempt 3
        advanceTimeBy(5000L)
        advanceUntilIdle()

        retryHelper.scheduleRetry(retryableError) // Attempt 4 - should exceed max
        advanceTimeBy(5000L)
        advanceUntilIdle()

        // Then
        val decision = retryHelper.retryDecisionFlow.value
        decision.shouldBeInstanceOf<RetryDecision.MaxRetriesReached>()
    }

    @Test
    fun testResetRetryState_thenResetsCountAndClearsFlow() = runTest(testDispatcher) {
        // Given
        val retryableError = SseError.NetworkError(java.io.IOException("Network error"))
        retryHelper.scheduleRetry(retryableError)
        advanceUntilIdle()

        // Verify we have a retry decision
        retryHelper.retryDecisionFlow.value.shouldBeInstanceOf<RetryDecision.RetryNow>()

        // When
        retryHelper.resetRetryState()
        advanceUntilIdle()

        // Then
        retryHelper.retryDecisionFlow.value.shouldBeNull()

        // Next retry should start from 1 again
        retryHelper.scheduleRetry(retryableError)
        advanceUntilIdle()
        val decision = retryHelper.retryDecisionFlow.value
        decision.shouldBeInstanceOf<RetryDecision.RetryNow>()
        (decision as RetryDecision.RetryNow).attemptCount.shouldBeEqualTo(1)
    }

    @Test
    fun testScheduleRetry_whenMultipleRetries_thenIncrementsCountCorrectly() = runTest(testDispatcher) {
        // Given
        val retryableError = SseError.NetworkError(java.io.IOException("Network error"))

        // When - Multiple retries
        retryHelper.scheduleRetry(retryableError) // 1
        advanceUntilIdle()
        (retryHelper.retryDecisionFlow.value as RetryDecision.RetryNow).attemptCount.shouldBeEqualTo(1)

        retryHelper.scheduleRetry(retryableError) // 2
        advanceTimeBy(5000L)
        advanceUntilIdle()
        (retryHelper.retryDecisionFlow.value as RetryDecision.RetryNow).attemptCount.shouldBeEqualTo(2)

        retryHelper.scheduleRetry(retryableError) // 3
        advanceTimeBy(5000L)
        advanceUntilIdle()
        (retryHelper.retryDecisionFlow.value as RetryDecision.RetryNow).attemptCount.shouldBeEqualTo(3)
    }

    @Test
    fun testScheduleRetry_whenTimeoutError_thenRetries() = runTest(testDispatcher) {
        // Given
        val timeoutError = SseError.TimeoutError

        // When
        retryHelper.scheduleRetry(timeoutError)
        advanceUntilIdle()

        // Then
        val decision = retryHelper.retryDecisionFlow.value
        decision.shouldBeInstanceOf<RetryDecision.RetryNow>()
    }

    @Test
    fun testScheduleRetry_whenServerErrorRetryable_thenRetries() = runTest(testDispatcher) {
        // Given
        val serverError = SseError.ServerError(
            throwable = Exception("Server error"),
            responseCode = 500,
            shouldRetry = true
        )

        // When
        retryHelper.scheduleRetry(serverError)
        advanceUntilIdle()

        // Then
        val decision = retryHelper.retryDecisionFlow.value
        decision.shouldBeInstanceOf<RetryDecision.RetryNow>()
    }

    @Test
    fun testScheduleRetry_whenServerErrorNonRetryable_thenDoesNotRetry() = runTest(testDispatcher) {
        // Given
        val serverError = SseError.ServerError(
            throwable = Exception("Bad request"),
            responseCode = 400,
            shouldRetry = false
        )

        // When
        retryHelper.scheduleRetry(serverError)
        advanceUntilIdle()

        // Then
        val decision = retryHelper.retryDecisionFlow.value
        decision.shouldBeInstanceOf<RetryDecision.RetryNotPossible>()
    }
}
