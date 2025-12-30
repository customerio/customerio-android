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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HeartbeatTimerTest : JUnitTest() {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val sseLogger = mockk<InAppSseLogger>(relaxed = true)

    private lateinit var heartbeatTimer: HeartbeatTimer

    @BeforeEach
    fun setup() {
        heartbeatTimer = HeartbeatTimer(sseLogger, testScope)
    }

    @Test
    fun testStartTimer_shouldEmitTimeoutAfterDelay() = runTest(testDispatcher) {
        // Given
        val timeoutMs = 1000L

        // When
        heartbeatTimer.startTimer(timeoutMs)
        advanceUntilIdle() // Wait for timer setup

        // Advance time to trigger timeout
        advanceTimeBy(timeoutMs + 100)
        advanceUntilIdle() // Wait for timeout emission

        // Then
        val event = heartbeatTimer.timeoutFlow.value
        event shouldBeEqualTo HeartbeatTimeoutEvent
    }

    @Test
    fun testResetTimer_shouldCancelPreviousTimer() = runTest(testDispatcher) {
        // Given
        val firstTimeoutMs = 2000L
        val secondTimeoutMs = 1000L

        // When
        heartbeatTimer.startTimer(firstTimeoutMs)
        advanceUntilIdle()

        // Reset timer before first timeout
        advanceTimeBy(500)
        heartbeatTimer.startTimer(secondTimeoutMs)
        advanceUntilIdle()

        // Advance time to trigger second timeout (but not first)
        advanceTimeBy(secondTimeoutMs + 100)
        advanceUntilIdle()

        // Then
        val event = heartbeatTimer.timeoutFlow.value
        event shouldBeEqualTo HeartbeatTimeoutEvent
    }

    @Test
    fun testCancellation_shouldHandleGracefully() = runTest(testDispatcher) {
        // Given
        val timeoutMs = 1000L

        // When
        heartbeatTimer.startTimer(timeoutMs)
        advanceUntilIdle()

        // Cancel timer before timeout by starting a new timer (which cancels the old one)
        advanceTimeBy(500)
        heartbeatTimer.startTimer(2000L)
        advanceUntilIdle()

        // Advance time to trigger the second timer timeout
        advanceTimeBy(2000L + 100)
        advanceUntilIdle()

        // Then - should emit timeout from second timer
        val event = heartbeatTimer.timeoutFlow.value
        event shouldBeEqualTo HeartbeatTimeoutEvent
    }

    @Test
    fun testRapidResets_shouldHandleProperCancellation() = runTest(testDispatcher) {
        // Given
        val timeoutMs = 1000L

        // When - Rapidly reset timer multiple times
        heartbeatTimer.startTimer(timeoutMs)
        advanceUntilIdle()
        advanceTimeBy(100)
        heartbeatTimer.startTimer(2000L)
        advanceUntilIdle()
        advanceTimeBy(100)
        heartbeatTimer.startTimer(3000L)
        advanceUntilIdle()
        advanceTimeBy(100)
        heartbeatTimer.startTimer(timeoutMs)
        advanceUntilIdle()

        // Advance time to trigger final timeout
        advanceTimeBy(timeoutMs + 100)
        advanceUntilIdle()

        // Then
        val event = heartbeatTimer.timeoutFlow.value
        event shouldBeEqualTo HeartbeatTimeoutEvent
    }

    @Test
    fun testZeroTimeout_shouldEmitImmediately() = runTest(testDispatcher) {
        // When
        heartbeatTimer.startTimer(0L)
        advanceUntilIdle()

        // Advance time slightly
        advanceTimeBy(100)
        advanceUntilIdle()

        // Then
        val event = heartbeatTimer.timeoutFlow.value
        event shouldBeEqualTo HeartbeatTimeoutEvent
    }

    @Test
    fun testNegativeTimeout_shouldEmitImmediately() = runTest(testDispatcher) {
        // When
        heartbeatTimer.startTimer(-1000L)
        advanceUntilIdle()

        // Advance time slightly
        advanceTimeBy(100)
        advanceUntilIdle()

        // Then
        val event = heartbeatTimer.timeoutFlow.value
        event shouldBeEqualTo HeartbeatTimeoutEvent
    }
}
