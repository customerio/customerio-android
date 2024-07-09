package io.customer.tracking.migration.queue

import io.customer.sdk.core.util.DispatchersProvider
import io.customer.sdk.core.util.Logger
import io.customer.tracking.migration.type.QueueStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

interface Queue {

    suspend fun run()
}

internal class QueueImpl internal constructor(
    private val dispatchersProvider: DispatchersProvider,
    private val storage: QueueStorage,
    private val runRequest: QueueRunRequest,
    private val logger: Logger
) : Queue {

    @Volatile
    var isRunningRequest: Boolean = false

    override suspend fun run() {
        synchronized(this) {
            val isQueueRunningRequest = isRunningRequest
            if (isQueueRunningRequest) return

            isRunningRequest = true
        }

        runRequest.run()

        synchronized(this) {
            // reset queue to be able to run again
            isRunningRequest = false
        }
    }

    internal fun runAsync() {
        CoroutineScope(dispatchersProvider.background).launch {
            run()
        }
    }

    private fun processQueueStatus(queueStatus: QueueStatus) {
        logger.debug("processing queue status $queueStatus")
        val isManyTasksInQueue =
            queueStatus.numTasksInQueue >= 0

        if (isManyTasksInQueue) {
            logger.info("queue met criteria to run automatically")

            runAsync()
        }
    }
}
