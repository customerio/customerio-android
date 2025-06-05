package io.customer.messaginginapp.ui.controller

import io.customer.messaginginapp.testutils.core.JUnitTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.Test

class ThreadSafeControllerTest : JUnitTest() {

    private class TestController : ThreadSafeController() {
        var simpleProperty: String? by threadSafe()
        var propertyWithDefault: Boolean? by threadSafe(false)
        var notificationProperty: String? by threadSafeWithNotification { old, new ->
            notificationCallCount.incrementAndGet()
            lastOldValue.set(old)
            lastNewValue.set(new)
        }
        val notificationCallCount = AtomicInteger(0)
        val lastOldValue = AtomicReference<String?>()
        val lastNewValue = AtomicReference<String?>()
    }

    @Test
    fun threadSafe_givenDefaultInitialization_expectNullValue() {
        val controller = TestController()

        controller.simpleProperty.shouldBeNull()
    }

    @Test
    fun threadSafe_givenDefaultValue_expectDefaultReturned() {
        val controller = TestController()

        controller.propertyWithDefault shouldBeEqualTo false
    }

    @Test
    fun threadSafe_givenValueSet_expectValueReturned() {
        val controller = TestController()
        val testValue = "test-value"

        controller.simpleProperty = testValue

        controller.simpleProperty shouldBeEqualTo testValue
    }

    @Test
    fun threadSafe_givenValueSetToNull_expectNullReturned() {
        val controller = TestController()
        controller.simpleProperty = "initial"

        controller.simpleProperty = null

        controller.simpleProperty.shouldBeNull()
    }

    @Test
    fun threadSafeWithNotification_givenValueSet_expectNotificationCalled() {
        val controller = TestController()
        val testValue = "test-value"

        controller.notificationProperty = testValue

        controller.notificationCallCount.get() shouldBeEqualTo 1
        controller.lastOldValue.get().shouldBeNull()
        controller.lastNewValue.get() shouldBeEqualTo testValue
    }

    @Test
    fun threadSafeWithNotification_givenValueChanged_expectNotificationWithOldValue() {
        val controller = TestController()
        val oldValue = "old-value"
        val newValue = "new-value"
        controller.notificationProperty = oldValue
        controller.notificationCallCount.set(0) // Reset counter

        controller.notificationProperty = newValue

        controller.notificationCallCount.get() shouldBeEqualTo 1
        controller.lastOldValue.get() shouldBeEqualTo oldValue
        controller.lastNewValue.get() shouldBeEqualTo newValue
    }

    @Test
    fun threadSafe_givenConcurrentAccess_expectNoDataCorruption() {
        val controller = TestController()
        val threadCount = 10
        val iterationsPerThread = 100
        val latch = CountDownLatch(threadCount)
        val completionLatch = CountDownLatch(threadCount)
        val values = mutableSetOf<String>()

        // Create multiple threads that set different values
        repeat(threadCount) { threadIndex ->
            thread {
                latch.countDown()
                latch.await() // Wait for all threads to be ready

                repeat(iterationsPerThread) { iteration ->
                    val value = "thread-$threadIndex-iteration-$iteration"
                    controller.simpleProperty = value
                    synchronized(values) {
                        values.add(value)
                    }
                    // Small delay to increase chance of interleaving
                    Thread.sleep(1)
                }
                completionLatch.countDown()
            }
        }

        // Wait for all threads to complete
        completionLatch.await(5, TimeUnit.SECONDS)

        // The final value should be one of the values that was set
        val finalValue = controller.simpleProperty
        if (finalValue != null) {
            values.contains(finalValue) shouldBeEqualTo true
        }
    }

    @Test
    fun threadSafe_givenConcurrentReadWrite_expectConsistentReads() {
        val controller = TestController()
        val readCount = 1000
        val writeValue = "consistent-value"
        val readValues = mutableListOf<String?>()
        val readLatch = CountDownLatch(1)
        val writeLatch = CountDownLatch(1)
        val completionLatch = CountDownLatch(2)

        // Writer thread
        thread {
            writeLatch.countDown()
            writeLatch.await()
            controller.simpleProperty = writeValue
            completionLatch.countDown()
        }

        // Reader thread
        thread {
            readLatch.countDown()
            readLatch.await()
            repeat(readCount) {
                readValues.add(controller.simpleProperty)
            }
            completionLatch.countDown()
        }

        completionLatch.await(5, TimeUnit.SECONDS)

        // All non-null values should be the write value (due to @Volatile visibility)
        readValues.filterNotNull().forEach { value ->
            value shouldBeEqualTo writeValue
        }
    }
}
