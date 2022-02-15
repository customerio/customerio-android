package io.customer.sdk.queue

import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.queue.type.QueueModifyResult
import io.customer.sdk.queue.type.QueueStatus
import io.customer.sdk.util.JsonAdapter
import io.customer.sdk.util.Logger

class Queue internal constructor(
    private val storage: QueueStorage,
    private val jsonAdapter: JsonAdapter,
    private val sdkConfig: CustomerIOConfig,
    private val logger: Logger
) {

    fun <TaskData: Any> addTask(type: String, data: TaskData): QueueModifyResult {
        logger.info("adding queue task $type")

        val taskDataString = jsonAdapter.toJson(data)

        val createTaskResult = storage.create(type, taskDataString)
        logger.debug("added queue task data $taskDataString")

        processQueueStatus(createTaskResult.queueStatus)

        return createTaskResult
    }

    private fun processQueueStatus(queueStatus: QueueStatus) {
        logger.debug("processing queue status $queueStatus")
        val isManyTasksInQueue = queueStatus.numTasksInQueue >= sdkConfig.backgroundQueueMinNumberOfTasks

        if (isManyTasksInQueue) {
            logger.info("queue met criteria to run automatically")

            // start running queue
        }
    }
}
