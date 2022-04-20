package io.customer.sdk.util

import android.os.CountDownTimer
import io.customer.sdk.utils.random

/**
 * Wrapper around an OS timer that gives us the ability to mock timers in tests to make them run faster.
 */
interface SimpleTimer {
    // after block is called, timer is reset to be ready to use again
    fun scheduleAndCancelPrevious(seconds: Seconds, block: () -> Unit)
    // after block is called, timer is reset to be ready to use again
    fun scheduleIfNotAlready(seconds: Seconds, block: () -> Unit): Boolean
    fun cancel()
}

class AndroidSimpleTimer(
    private val logger: Logger
) : SimpleTimer {

    @Volatile private var countdownTimer: CountDownTimer? = null
    @Volatile private var timerAlreadyScheduled = false
    private val instanceIdentifier = String.random

    override fun scheduleAndCancelPrevious(seconds: Seconds, block: () -> Unit) {
        val newTimer: CountDownTimer = synchronized(this) {
            timerAlreadyScheduled = true
            unsafeCancel()

            log("making a timer for $seconds")

            object : CountDownTimer(seconds.toMilliseconds.value, 1) {
                override fun onTick(millisUntilFinished: Long) {}
                override fun onFinish() {
                    timerDone() // reset timer before calling block as block might be synchronous and if it tries to start a new timer, it will not succeed because we need to reset the timer.
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
    }

    private fun log(message: String) {
        logger.debug("Timer $instanceIdentifier $message")
    }
}
