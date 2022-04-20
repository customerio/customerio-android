package io.customer.common_test

import io.customer.sdk.util.Seconds
import java.lang.RuntimeException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Convenient class used to test async functions in a test function.
 *
 * ```
 * val asyncWait = AsyncWait(1)
 * asyncFunctionUnderTest.start {
 *   asyncWait.fulfill() // will succeed test if this is called.
 * }
 * asyncWait.wait() // will fail test if timeout met
 * ```
 */
class AsyncWait(val expectedFulfills: Int, val isInverted: Boolean = false) {
    private val latch = CountDownLatch(expectedFulfills)

    fun fulfill() {
        if (latch.count <= 1 && isInverted) throw RuntimeException("Async test fulfilled to 0, but test is inverted and did not want to fulfill to 0.")

        latch.countDown()
    }

    // Note: wait() will block the current thread. It's recommended that the asynchronous code under test is on another thread and will call fulfill() on another thread or the test function will be blocked.
    fun wait(timeout: Seconds = Seconds(0.3)) {
        latch.await(timeout.toMilliseconds.value, TimeUnit.MILLISECONDS)
    }
}
