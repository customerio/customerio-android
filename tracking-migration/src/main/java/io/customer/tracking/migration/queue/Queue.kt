package io.customer.tracking.migration.queue

import io.customer.sdk.core.util.Logger

interface Queue {
    suspend fun run()
}

internal class QueueImpl internal constructor(
    private val logger: Logger,
    private val runRequest: QueueRunRequest
) : Queue {

    @Volatile
    var isRunningRequest: Boolean = false

    override suspend fun run() {
        synchronized(this) {
            val isQueueRunningRequest = isRunningRequest
            if (isQueueRunningRequest) return

            isRunningRequest = true
        }

        logger.debug("Running migration queue...")
        runRequest.run()

        synchronized(this) {
            // reset queue to be able to run again
            isRunningRequest = false
        }
    }
}
