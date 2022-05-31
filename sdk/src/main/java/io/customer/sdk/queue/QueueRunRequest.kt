package io.customer.sdk.queue

import io.customer.sdk.error.CustomerIOError
import io.customer.sdk.queue.type.QueueInventory
import io.customer.sdk.queue.type.QueueTaskMetadata
import io.customer.sdk.util.Logger

interface QueueRunRequest {
    suspend fun run()
}

class QueueRunRequestImpl internal constructor(
    private val runner: QueueRunner,
    private val queueStorage: QueueStorage,
    private val logger: Logger,
    private val queryRunner: QueueQueryRunner
) : QueueRunRequest {

    override suspend fun run() {
        logger.debug("queue starting to run tasks...")
        val inventory = queueStorage.getInventory()

        runTasks(inventory, inventory.count())
    }

    private suspend fun runTasks(inventory: QueueInventory, totalNumberOfTasksToRun: Int, lastFailedTask: QueueTaskMetadata? = null) {
        val nextTaskToRunInventoryItem = queryRunner.getNextTask(inventory, lastFailedTask)
        if (nextTaskToRunInventoryItem == null) {
            logger.debug("queue done running tasks")

            queryRunner.reset()

            return
        }

        val nextTaskStorageId = nextTaskToRunInventoryItem.taskPersistedId
        val nextTaskToRun = queueStorage.get(nextTaskStorageId)
        if (nextTaskToRun == null) {
            logger.error("tried to get queue task with storage id: $nextTaskStorageId but storage couldn't find it.")

            // The task failed to execute because it couldn't be found. Gracefully handle the scenario by
            // behaving the same way a failed HTTP request does. Run next task with an updated `lastFailedTask`.
            return goToNextTask(inventory, totalNumberOfTasksToRun, nextTaskToRunInventoryItem)
        }

        logger.debug("queue tasks left to run: ${inventory.count()} out of $totalNumberOfTasksToRun")
        logger.debug("queue next task to run: $nextTaskStorageId, ${nextTaskToRun.type}, ${nextTaskToRun.data}, ${nextTaskToRun.runResults}")

        val result = runner.runTask(nextTaskToRun)
        when {
            result.isSuccess -> {
                logger.debug("queue task $nextTaskStorageId ran successfully")

                logger.debug("queue deleting task $nextTaskStorageId")
                queueStorage.delete(nextTaskStorageId)

                return goToNextTask(inventory, totalNumberOfTasksToRun, null)
            }
            result.isFailure -> {
                val error = result.exceptionOrNull()
                logger.debug("queue task $nextTaskStorageId run failed $error")

                val customerIOError = error as? CustomerIOError
                return if (customerIOError is CustomerIOError.HttpRequestsPaused) {
                    // When HTTP requests are paused, don't increment metadata about the tasks to create inaccurate data
                    logger.info("queue is quitting early because all HTTP requests are paused.")

                    goToNextTask(emptyList(), totalNumberOfTasksToRun, null)
                } else {
                    val previousRunResults = nextTaskToRun.runResults
                    val newRunResults = nextTaskToRun.runResults.copy(totalRuns = previousRunResults.totalRuns + 1)
                    logger.debug("queue task $nextTaskStorageId, updating run history from: $previousRunResults to: $newRunResults")
                    queueStorage.update(nextTaskStorageId, newRunResults)

                    goToNextTask(inventory, totalNumberOfTasksToRun, nextTaskToRunInventoryItem)
                }
            }
        }
    }

    private suspend fun goToNextTask(inventory: QueueInventory, totalNumberOfTasksToRun: Int, lastFailedTask: QueueTaskMetadata?) {
        val newInventory = inventory.toMutableList()
        newInventory.removeFirstOrNull()
        runTasks(newInventory, totalNumberOfTasksToRun, lastFailedTask)
    }
}
