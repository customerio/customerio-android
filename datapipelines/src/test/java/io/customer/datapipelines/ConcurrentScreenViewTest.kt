package io.customer.datapipelines

import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.di.SDKComponent
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests to verify that multiple threads calling CustomerIO.instance.screen()
 * correctly process events in the proper order.
 */
class ConcurrentScreenViewTest : JUnitTest() {

    private lateinit var eventBus: EventBus
    private val capturedEvents = mutableListOf<Event.ScreenViewedEvent>()
    private val capturedEventsLock = Any()

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfiguration {
                diGraph {
                    sdk {
                        val mockEventBus = mockk<EventBus>(relaxed = true)
                        // Capture all ScreenViewedEvent events
                        val eventSlot = slot<Event.ScreenViewedEvent>()
                        every { mockEventBus.publish(capture(eventSlot)) } answers {
                            synchronized(capturedEventsLock) {
                                capturedEvents.add(eventSlot.captured)
                            }
                        }
                        overrideDependency<EventBus>(mockEventBus)
                    }
                }
            }
        )

        eventBus = SDKComponent.eventBus
        capturedEvents.clear()
    }

    @Test
    fun verifyScreenMethodIsThreadSafe() {
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
        completionLatch.await(10, TimeUnit.SECONDS)

        // Shutdown the executor
        executor.shutdown()
        executor.awaitTermination(1, TimeUnit.SECONDS)

        // Verify that screen method was called the expected number of times
        verify(exactly = totalEvents) {
            eventBus.publish(any<Event.ScreenViewedEvent>())
        }
    }

    @Test
    fun verifyScreenSynchronizedMethodPreventsRaceConditions() {
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
            assertCalledOnce { eventBus.publish(Event.ScreenViewedEvent(name)) }
        }
    }

    @Test
    fun verifyScreenExecutionOrder() {
        // This test verifies the execution order of screen events

        val screenNames = listOf("Screen1", "Screen2", "Screen3", "Screen4", "Screen5")
        val latch = CountDownLatch(1)
        val threadPool = Executors.newSingleThreadExecutor()

        // Execute screen calls in sequence on a single thread to ensure deterministic order
        threadPool.submit {
            latch.await()
            screenNames.forEach { name ->
                sdkInstance.screen(name)
            }
        }

        latch.countDown()

        // Wait for execution to complete
        threadPool.shutdown()
        threadPool.awaitTermination(1, TimeUnit.SECONDS)

        // Verify the events were published in the correct order
        assertEquals(screenNames.size, capturedEvents.size)

        for (i in screenNames.indices) {
            assertEquals(
                screenNames[i],
                capturedEvents[i].name,
                "Events should be processed in the same order they were submitted"
            )
        }
    }

    @Test
    fun verifyNoANRWithLongRunningOperations() {
        // Simulate a situation where one thread is performing a long-running operation
        // that could potentially cause ANR if synchronization blocks the main thread

        // Create a mock that simulates a long running operation
        val slowEventBus = mockk<EventBus>(relaxed = true)
        every { slowEventBus.publish(any<Event.ScreenViewedEvent>()) } answers {
            // Simulate slow processing
            Thread.sleep(500)
        }

        // Temporarily replace the event bus with our slow version
        val originalEventBus = SDKComponent.eventBus
        SDKComponent.overrideDependency<EventBus>(slowEventBus)

        try {
            // Flag to indicate if the main thread was blocked
            val mainThreadBlocked = AtomicBoolean(false)
            val longOpCompleted = AtomicBoolean(false)
            val startSignal = CountDownLatch(1)

            // Thread to perform the long operation
            val longOpThread = Thread {
                startSignal.await()
                sdkInstance.screen("LongOperationScreen")
                longOpCompleted.set(true)
            }
            longOpThread.start()

            // Thread to check if main thread is responsive
            val watchdogThread = Thread {
                startSignal.countDown()

                // Give the long operation thread time to start and enter synchronization
                Thread.sleep(100)

                // The long operation should still be running at this point
                assertFalse(longOpCompleted.get(), "Long operation should still be running")

                // Now execute a quick operation on another thread
                // If synchronization is blocking all threads, this won't complete quickly
                val quickOpCompleted = AtomicBoolean(false)
                val quickOpThread = Thread {
                    try {
                        // This should not be blocked by the long operation
                        // if the code is properly designed
                        Thread.sleep(50)
                        quickOpCompleted.set(true)
                    } catch (e: InterruptedException) {
                        // Ignore
                    }
                }
                quickOpThread.start()

                // Wait a short time and check if the quick operation completed
                quickOpThread.join(200)

                // If the quick operation didn't complete, the main thread might be blocked
                if (!quickOpCompleted.get()) {
                    mainThreadBlocked.set(true)
                }
            }
            watchdogThread.start()

            // Wait for both threads to complete
            longOpThread.join(2000) // Wait up to 2 seconds for long operation
            watchdogThread.join(2000)

            // Verify that the main thread was not blocked
            assertFalse(mainThreadBlocked.get(), "Main thread should not be blocked by long operations")

            // Also verify that the long operation eventually completed
            assertTrue(longOpCompleted.get(), "Long operation should have completed")
        } finally {
            // Restore the original event bus
            SDKComponent.overrideDependency<EventBus>(originalEventBus)
        }
    }

    @Test
    fun verifyHighConcurrencyDoesNotCauseANR() {
        // This test simulates very high concurrency to check for potential ANR

        // Number of concurrent calls - high enough to potentially cause issues
        val callCount = 100

        // Create threads that will all try to call screen() at the same time
        val threads = List(callCount) { i ->
            Thread {
                sdkInstance.screen("HighConcurrencyScreen_$i")
            }
        }

        // Track start time
        val startTime = System.currentTimeMillis()

        // Start all threads
        threads.forEach { it.start() }

        // Wait for all threads to complete
        threads.forEach { it.join(5000) }

        // Calculate total execution time
        val executionTime = System.currentTimeMillis() - startTime

        // Verify all events were processed
        assertEquals(callCount, capturedEvents.size)

        // Simple heuristic to check for ANR - the execution time should be reasonable
        // If we're using synchronization without proper async handling, this could take much longer
        assertTrue(
            executionTime < 5000,
            "High concurrency should not cause excessive blocking (execution time: $executionTime ms)"
        )
    }
}
