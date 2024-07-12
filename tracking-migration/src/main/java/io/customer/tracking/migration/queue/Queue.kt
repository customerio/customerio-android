package io.customer.tracking.migration.queue

import io.customer.sdk.core.util.DispatchersProvider
import io.customer.sdk.core.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

interface Queue {
    suspend fun run()
    fun runAsync()
}

internal class QueueImpl internal constructor(
    private val dispatchersProvider: DispatchersProvider,
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

    override fun runAsync() {
        logger.debug("Starting migration queue runner...")
        CoroutineScope(dispatchersProvider.background).launch {
            run()
        }
    }
}
