package io.customer.sdk.util

import android.os.CountDownTimer
import io.customer.sdk.extensions.random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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

internal class AndroidSimpleTimer(
    private val logger: Logger,
    private val dispatchersProvider: DispatchersProvider
) : SimpleTimer {

    @Volatile private var countdownTimer: CountDownTimer? = null

    @Volatile private var startTimerMainThreadJob: Job? = null

    @Volatile private var timerAlreadyScheduled = false
    private val instanceIdentifier = String.random

    override fun scheduleAndCancelPrevious(seconds: Seconds, block: () -> Unit) {
        // Must create and start timer on the main UI thread or Android will throw an exception saying the current thread doesn't have a Looper.
        // Because we are starting a new coroutine, there is a chance that there could be a delay in starting the timer. This is OK because
        // this function is designed to be async anyway so the logic from the caller has not changed.
        startTimerMainThreadJob = CoroutineScope(dispatchersProvider.main).launch {
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
                }
            }

            this@AndroidSimpleTimer.countdownTimer = newTimer

            newTimer.start()
        }
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
        try {
            startTimerMainThreadJob?.cancel()
        } catch (e: Throwable) {
            // cancel() throws an exception. We want to cancel so ignore the error thrown.
        }

        countdownTimer?.cancel()
        countdownTimer = null
    }

    private fun log(message: String) {
        logger.debug("Timer $instanceIdentifier $message")
    }
}
