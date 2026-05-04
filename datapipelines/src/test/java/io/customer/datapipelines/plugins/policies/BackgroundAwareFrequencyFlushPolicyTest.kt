package io.customer.datapipelines.plugins.policies

import com.segment.analytics.kotlin.core.Analytics
import io.customer.commontest.config.TestConfig
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.util.AppForegroundState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundAwareFrequencyFlushPolicyTest : JUnitTest(dispatcher = StandardTestDispatcher()) {

    private val mockAnalytics = mockk<Analytics>(relaxed = true)
    private val mockForegroundState = mockk<AppForegroundState>()

    private val testScope get() = delegate.testScope

    private val flushIntervalMs = 1000L

    private fun newPolicy() = BackgroundAwareFrequencyFlushPolicy(
        flushIntervalInMillis = flushIntervalMs,
        foregroundState = mockForegroundState
    )

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfig)
        every { mockAnalytics.analyticsScope } returns testScope
        every { mockAnalytics.fileIODispatcher } returns testDispatcher
    }

    @Test
    fun schedule_givenForeground_expectsFlushOnEachTick() {
        every { mockForegroundState.isInForeground } returns true

        val policy = newPolicy()

        policy.schedule(mockAnalytics)
        testScope.runCurrent()
        verify(exactly = 1) { mockAnalytics.flush() }

        testScope.advanceTimeBy(flushIntervalMs + 1)
        testScope.runCurrent()
        verify(exactly = 2) { mockAnalytics.flush() }

        policy.unschedule()
    }

    @Test
    fun schedule_givenBackground_expectsNoFlush() {
        every { mockForegroundState.isInForeground } returns false

        val policy = newPolicy()

        policy.schedule(mockAnalytics)
        testScope.advanceTimeBy(flushIntervalMs * 3 + 1)
        testScope.runCurrent()

        verify(exactly = 0) { mockAnalytics.flush() }

        policy.unschedule()
    }

    @Test
    fun schedule_givenForegroundThenBackgroundThenForeground_expectsPauseAndResume() {
        every { mockForegroundState.isInForeground } returns true

        val policy = newPolicy()

        policy.schedule(mockAnalytics)
        testScope.runCurrent()
        verify(exactly = 1) { mockAnalytics.flush() }

        every { mockForegroundState.isInForeground } returns false
        testScope.advanceTimeBy(flushIntervalMs + 1)
        testScope.runCurrent()
        verify(exactly = 1) { mockAnalytics.flush() }

        testScope.advanceTimeBy(flushIntervalMs + 1)
        testScope.runCurrent()
        verify(exactly = 1) { mockAnalytics.flush() }

        every { mockForegroundState.isInForeground } returns true
        testScope.advanceTimeBy(flushIntervalMs + 1)
        testScope.runCurrent()
        verify(exactly = 2) { mockAnalytics.flush() }

        policy.unschedule()
    }

    @Test
    fun schedule_calledTwice_expectsOnlyOneCoroutine() {
        every { mockForegroundState.isInForeground } returns true

        val policy = newPolicy()

        policy.schedule(mockAnalytics)
        testScope.runCurrent()
        policy.schedule(mockAnalytics)
        testScope.runCurrent()

        verify(exactly = 1) { mockAnalytics.flush() }

        testScope.advanceTimeBy(flushIntervalMs + 1)
        testScope.runCurrent()
        // Two flushes (initial + one tick) confirms a single loop is running, not two.
        verify(exactly = 2) { mockAnalytics.flush() }

        policy.unschedule()
    }

    @Test
    fun schedule_calledAfterUnschedule_expectsNewCoroutineLaunched() {
        every { mockForegroundState.isInForeground } returns true

        val policy = newPolicy()

        policy.schedule(mockAnalytics)
        testScope.runCurrent()
        verify(exactly = 1) { mockAnalytics.flush() }

        policy.unschedule()

        policy.schedule(mockAnalytics)
        testScope.runCurrent()
        verify(exactly = 2) { mockAnalytics.flush() }

        testScope.advanceTimeBy(flushIntervalMs + 1)
        testScope.runCurrent()
        verify(exactly = 3) { mockAnalytics.flush() }

        policy.unschedule()
    }

    @Test
    fun unschedule_givenScheduled_expectsCoroutineCancelled() {
        every { mockForegroundState.isInForeground } returns true

        val policy = newPolicy()

        policy.schedule(mockAnalytics)
        testScope.runCurrent()
        verify(exactly = 1) { mockAnalytics.flush() }

        policy.unschedule()
        testScope.advanceTimeBy(flushIntervalMs * 5 + 1)
        testScope.runCurrent()

        verify(exactly = 1) { mockAnalytics.flush() }
    }

    @Test
    fun unschedule_calledWithoutSchedule_expectsNoOp() {
        val policy = newPolicy()

        policy.unschedule()
    }

    @Test
    fun shouldFlush_expectsFalse() {
        val policy = newPolicy()

        policy.shouldFlush() shouldBeEqualTo false
    }
}
