package io.customer.sdk.util

import android.os.CountDownTimer
import io.customer.sdk.utils.random
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.lang.Exception

/**
 * Wrapper around an OS timer that gives us the ability to mock timers in tests to make them run faster.
 */
interface SimpleTimer {
    // after block is called, timer is reset to be ready to use again
    fun scheduleAndCancelPrevious(seconds: Seconds, block: () -> Unit)
    // after block is called, timer is reset to be ready to use again
    suspend fun <T> scheduleAndCancelPreviousSuspend(seconds: Seconds, block: suspend () -> T): T
    // after block is called, timer is reset to be ready to use again
    fun scheduleIfNotAlready(seconds: Seconds, block: () -> Unit): Boolean
    fun cancel()
}

class AndroidSimpleTimer(
    private val logger: Logger
) : SimpleTimer {

    @Volatile private var countdownTimer: CountDownTimer? = null
    @Volatile private var coroutineTimer: Deferred<*>? = null
    @Volatile private var timerAlreadyScheduled = false
    private val instanceIdentifier = String.random

    override suspend fun <T> scheduleAndCancelPreviousSuspend(
        seconds: Seconds,
        block: suspend () -> T
    ): T {
        return coroutineScope {
            // using deferred as it allows us to cancel our timer.
            val newTimer: Deferred<T> = synchronized(this@AndroidSimpleTimer) {
                timerAlreadyScheduled = true
                unsafeCancel()

                log("making a timer for $seconds")

                async {
                    delay(seconds.toMilliseconds.value)

                    val value = block()
                    // only reset *after* block has been called to be sure the job is done once.
                    timerDone()
                    value
                }
            }.also {
                coroutineTimer = it
            }

            newTimer.await()
        }
    }

    override fun scheduleAndCancelPrevious(seconds: Seconds, block: () -> Unit) {
        val newTimer: CountDownTimer = synchronized(this) {
            timerAlreadyScheduled = true
            unsafeCancel()
            val millisecondsIntoFuture = seconds.toMilliseconds.value

            log("making a timer for $millisecondsIntoFuture milliseconds ($seconds seconds)")

            object : CountDownTimer(millisecondsIntoFuture, 1) {
                override fun onTick(millisUntilFinished: Long) {}
                override fun onFinish() {
                    timerDone() // reset timer before calling block as block might be synchronous and if it tries to start a new timer, it will not succeed.
                    block()
                }
            }.also {
                this.countdownTimer = it
            }
        }

        newTimer.start()
    }

    override fun scheduleIfNotAlready(seconds: Seconds, block: () -> Unit): Boolean {
        synchronized(this) {
            if (timerAlreadyScheduled) {
                log("already scheduled to run. Skipping request.")
                return false
            }

            scheduleAndCancelPrevious(seconds, block)

            return true
        }
    }

    private fun timerDone() {
        synchronized(this) {
            unsafeCancel() // reset the timer vars.
            timerAlreadyScheduled = false

            log("timer is done! It's been reset")
        }
    }

    override fun cancel() {
        synchronized(this) {
            log("timer is being cancelled")
            unsafeCancel()

            timerAlreadyScheduled = false
        }
    }

    // cancel without having a mutex lock. Call within a synchronized{} block
    private fun unsafeCancel() {
        countdownTimer?.cancel()
        countdownTimer = null

        try {
            coroutineTimer?.cancel()
        } catch (e: Exception) {
            // cancel() throws an error. Ignore it as we purposely want to cancel.
        }
        coroutineTimer = null
    }

    private fun log(message: String) {
        logger.debug("Timer $instanceIdentifier $message")
    }
}
