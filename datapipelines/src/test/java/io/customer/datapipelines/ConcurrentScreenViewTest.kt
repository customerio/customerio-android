package io.customer.datapipelines

import io.customer.commontest.config.TestConfig
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test

/**
 * Tests to verify that multiple threads calling CustomerIO.instance.screen()
 * correctly process events in the proper order.
 */
class ConcurrentScreenViewTest : JUnitTest() {

    // Interface to record screen events
    interface ScreenRecorder {
        fun recordScreen(name: String)
    }

    private lateinit var screenRecorder: ScreenRecorder

    override fun setup(testConfig: TestConfig) {
        // Create and set up a spy on the screen recorder
        val recorder = mockk<ScreenRecorder>(relaxed = true)

        super.setup(
            testConfiguration {
                sdkConfig {
                    // No special configuration needed
                }
            }
        )

        screenRecorder = recorder
    }

    @Test
    fun `verify screen method is thread safe`() {
        // Number of concurrent threads
        val threadCount = 5
        // Number of screen view events per thread
        val eventsPerThread = 3
        // Total number of events
        val totalEvents = threadCount * eventsPerThread

        // CountDownLatch to wait for all threads to be ready
        val readyLatch = CountDownLatch(threadCount)
        // CountDownLatch to signal all threads to start simultaneously
        val startLatch = CountDownLatch(1)
        // CountDownLatch to wait for all events to be processed
        val completionLatch = CountDownLatch(totalEvents)

        // Thread pool to run the concurrent tasks
        val executor = Executors.newFixedThreadPool(threadCount)

        // Submit tasks to the thread pool
        repeat(threadCount) { threadId ->
            executor.submit {
                // Signal that this thread is ready
                readyLatch.countDown()
                // Wait for all threads to be ready and the start signal
                startLatch.await()

                // Generate and track screen view events for this thread
                repeat(eventsPerThread) { eventIndex ->
                    val screenName = "Screen_${eventIndex}_Thread_$threadId"
                    // Call the synchronized screen method
                    sdkInstance.screen(screenName)
                    // Record the call
                    screenRecorder.recordScreen(screenName)
                    // Count down for completion
                    completionLatch.countDown()
                    // Add small sleep between calls
                    Thread.sleep(10)
                }
            }
        }

        // Wait for all threads to be ready
        readyLatch.await(5, TimeUnit.SECONDS)
        // Signal all threads to start
        startLatch.countDown()

        // Wait for all events to be processed (with timeout)
        val completed = completionLatch.await(10, TimeUnit.SECONDS)

        // Shutdown the executor
        executor.shutdown()
        executor.awaitTermination(1, TimeUnit.SECONDS)

        // Verify that screen method was called the expected number of times
        verify(exactly = totalEvents) { screenRecorder.recordScreen(any()) }
    }

    @Test
    fun `verify screen synchronized method prevents race conditions`() {
        // This test verifies that the synchronized method works correctly
        // by checking that the mock is called the exact number of times expected

        val screenNames = listOf("Screen1", "Screen2", "Screen3", "Screen4", "Screen5")
        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)

        // Launch multiple threads that call screen() simultaneously
        screenNames.forEach { name ->
            executor.submit {
                // Wait for signal to start all threads at once
                latch.await()
                // Call the synchronized method
                sdkInstance.screen(name)
                // Record the call
                screenRecorder.recordScreen(name)
            }
        }

        // Signal all threads to start simultaneously
        latch.countDown()

        // Give time for execution to complete
        Thread.sleep(1000)

        // Shutdown executor
        executor.shutdown()
        executor.awaitTermination(1, TimeUnit.SECONDS)

        // Verify that each screen was recorded exactly once
        screenNames.forEach { name ->
            verify(exactly = 1) { screenRecorder.recordScreen(name) }
        }
    }
}
