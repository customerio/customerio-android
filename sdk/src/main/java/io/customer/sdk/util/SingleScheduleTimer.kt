package io.customer.sdk.util

import io.customer.sdk.queue.Queue

interface QueueTimer : SimpleTimer

/**
 * Since [Queue] isn't a singleton, we need a timer to be a singleton so we don't start a new timer
 * for each queue instance created.
 */
class QueueTimerImpl private constructor(
    private val timer: SimpleTimer
) : QueueTimer {

    class Factory(private val timer: SimpleTimer) : SandboxedSingleton<QueueTimer>() {
        override fun getNewInstance(siteId: String): QueueTimer {
            return QueueTimerImpl(timer)
        }
    }

    override fun scheduleAndCancelPrevious(seconds: Seconds, block: () -> Unit) {}

    override fun scheduleIfNotAlready(seconds: Seconds, block: () -> Unit): Boolean {
        return timer.scheduleIfNotAlready(seconds, block)
    }

    override fun cancel() {
        timer.cancel()
    }
}
