package io.customer.commontest.extensions

import android.os.Handler
import android.os.Looper
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.schedule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Executes an action after a delay on the main thread and waits for completion.
 * Useful for testing async behavior with precise timing control. Default 0ms delay
 * still ensures the action runs after the current execution cycle completes.
 */
inline fun postOnUiThread(
    delay: Long = 0L,
    timeout: Long = delay + 1_000L,
    crossinline action: TimerTask.() -> Unit
) {
    val latch = CountDownLatch(1)

    Timer().schedule(delay) {
        // Ensure the action runs on the main thread
        Handler(Looper.getMainLooper()).post {
            action()
        }
        latch.countDown()
    }

    if (!latch.await(timeout, TimeUnit.MILLISECONDS)) {
        error("Scheduled block did not complete within timeout of $timeout after delay of $delay")
    }
}

/**
 * Checks if the current thread is the main thread.
 */
private val isMainThread: Boolean
    get() = Looper.myLooper() == Looper.getMainLooper()

/**
 * Asserts that the current code is executing on the main thread.
 */
fun assertOnMainThread() {
    assertTrue(
        "Expected to be on main thread but was on ${Thread.currentThread().name}",
        isMainThread
    )
}

/**
 * Asserts that the current code is executing on a background thread.
 */
fun assertNotOnMainThread() {
    assertFalse("Expected to be on background thread but was on main thread", isMainThread)
}
