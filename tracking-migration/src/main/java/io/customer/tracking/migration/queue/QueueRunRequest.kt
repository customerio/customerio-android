package io.customer.tracking.migration.queue

import io.customer.sdk.core.util.Logger

interface QueueRunRequest {
    suspend fun run()
}

internal class QueueRunRequestImpl internal constructor(
    private val runner: QueueRunner,
    private val queueStorage: QueueStorage,
    private val logger: Logger,
    private val queryRunner: QueueQueryRunner
) : QueueRunRequest {

    override suspend fun run() {
        logger.debug("queue starting to run tasks...")
        val inventory = queueStorage.getInventory()
        val tasksToRun = inventory.toMutableList()

        while (tasksToRun.isNotEmpty()) {
            // get the next task to run using the query criteria
            val currentTaskMetadata = queryRunner.getNextTask(tasksToRun)
            if (currentTaskMetadata == null) {
                logger.debug("all queue tasks have been migrated or failed to run. Exiting queue run.")
                break
            }

            tasksToRun.remove(currentTaskMetadata)

            val taskStorageId = currentTaskMetadata.taskPersistedId
            val taskToRun = queueStorage.get(taskStorageId)

            if (taskToRun == null) {
                logger.error("Tried to get queue task with storage id: $taskStorageId but storage couldn't find it.")
                // The task failed to execute because it couldn't be found. Which means it's a failed task
                // If we can't find the task, we can't run it, so we should delete it from the queue
                queueStorage.delete(taskStorageId)
                continue
            }

            logger.debug("queue tasks left to run: ${tasksToRun.size}")
            logger.debug("queue next task to run: $taskStorageId, ${taskToRun.type}, ${taskToRun.data}, ${taskToRun.runResults}")

            val result = runner.runTask(taskToRun)

            when {
                result.isSuccess -> {
                    logger.debug("queue task $taskStorageId ran successfully")
                    logger.debug("queue deleting task $taskStorageId")
                }
                result.isFailure -> {
                    val error = result.exceptionOrNull()
                    logger.debug("queue task $taskStorageId run failed $error")
                }
            }

            // delete the task from the queue regardless of success or failure
            // this task has been forwarded to the data pipeline so we are going to delete it regardless of success or failure
            queueStorage.delete(taskStorageId)
        }
        logger.debug("queue done running tasks")
    }
}
