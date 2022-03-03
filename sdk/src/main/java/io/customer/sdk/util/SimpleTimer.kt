package io.customer.sdk.util

import android.os.CountDownTimer
import io.customer.sdk.utils.random

/**
 * Wrapper around an OS timer that gives us the ability to mock timers in tests to make them run faster.
 */
interface SimpleTimer {
    fun scheduleAndCancelPrevious(seconds: Seconds, block: () -> Unit)
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
        synchronized(this) {
            unsafeCancel()

            log("making a timer for $seconds seconds")

            countdownTimer = object : CountDownTimer(seconds.numberOfMilliseconds, 100) {
                override fun onTick(millisUntilFinished: Long) {}
                override fun onFinish() {
                    synchronized(this) {
                        timerAlreadyScheduled = false
                        countdownTimer = null

                        log("timer is done! It's been reset")

                        block()
                    }
                }
            }
        }
    }

    override fun scheduleIfNotAlready(seconds: Seconds, block: () -> Unit): Boolean {
        synchronized(this) {
            if (timerAlreadyScheduled) {
                log("already scheduled to run. Skipping request.")
                return false
            }

            timerAlreadyScheduled = true

            scheduleAndCancelPrevious(seconds, block)

            return true
        }
    }

    override fun cancel() {
        synchronized(this) {
            timerAlreadyScheduled = false

            log("timer is being cancelled")
            unsafeCancel()
        }
    }

    private fun unsafeCancel() {
        countdownTimer?.cancel()
        countdownTimer = null
    }

    private fun log(message: String) {
        logger.debug("Timer $instanceIdentifier $message")
    }
}
