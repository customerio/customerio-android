package io.customer.sdk.queue

import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.queue.type.QueueModifyResult
import io.customer.sdk.queue.type.QueueStatus
import io.customer.sdk.util.JsonAdapter
import io.customer.sdk.util.Logger
import io.customer.sdk.util.QueueTimer
import io.customer.sdk.util.Seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

interface Queue {
    fun <TaskType : Enum<*>, TaskData : Any> addTask(type: TaskType, data: TaskData): QueueModifyResult
}

class QueueImpl internal constructor(
    private val storage: QueueStorage,
    private val runRequest: QueueRunRequest,
    private val jsonAdapter: JsonAdapter,
    private val sdkConfig: CustomerIOConfig,
    private val queueTimer: QueueTimer,
    private val logger: Logger
) : Queue {

    private val numberSecondsToScheduleTimer: Seconds
        get() = Seconds(sdkConfig.backgroundQueueSecondsDelay)

    override fun <TaskType : Enum<*>, TaskData : Any> addTask(type: TaskType, data: TaskData): QueueModifyResult {
        logger.info("adding queue task $type")

        val taskDataString = jsonAdapter.toJson(data)

        val createTaskResult = storage.create(type.name, taskDataString)
        logger.debug("added queue task data $taskDataString")

        processQueueStatus(createTaskResult.queueStatus)

        return createTaskResult
    }

    private fun run() {
        CoroutineScope(Dispatchers.IO).launch {
            runRequest.startIfNotAlready()
        }
    }

    private fun processQueueStatus(queueStatus: QueueStatus) {
        logger.debug("processing queue status $queueStatus")
        val isManyTasksInQueue = queueStatus.numTasksInQueue >= sdkConfig.backgroundQueueMinNumberOfTasks

        if (isManyTasksInQueue) {
            logger.info("queue met criteria to run automatically")

            queueTimer.cancel()

            this.run()
        } else {
            // Not enough tasks in the queue yet to run it now, so let's schedule them to run in the future.
            // It's expected that only 1 timer instance exists and is running in the SDK.
            val didSchedule = queueTimer.scheduleIfNotAlready(numberSecondsToScheduleTimer) {
                logger.info("queue timer: now running queue")

                this.run()
            }

            if (didSchedule) logger.info("queue timer: scheduled to run queue in $numberSecondsToScheduleTimer seconds")
        }
    }
}
