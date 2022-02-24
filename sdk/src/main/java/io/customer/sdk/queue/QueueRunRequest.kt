package io.customer.sdk.queue

import io.customer.sdk.queue.type.QueueInventory
import io.customer.sdk.queue.type.QueueRunner
import io.customer.sdk.queue.type.QueueTaskMetadata
import io.customer.sdk.util.Logger

interface QueueRunRequest {
    suspend fun startIfNotAlready()
}

class QueueRunRequestImpl internal constructor(
    private val runner: QueueRunner,
    private val queueStorage: QueueStorage,
    private val logger: Logger,
    private val requestManager: QueueRequestManager
) : QueueRunRequest {

    override suspend fun startIfNotAlready() {
        val isRequestCurrentlyRunning = requestManager.startRequest()

        if (!isRequestCurrentlyRunning) {
            startNewRequest()
        }
    }

    private suspend fun startNewRequest() {
        logger.debug("queue starting to run tasks...")
        val inventory = queueStorage.getInventory()

        runTasks(inventory, inventory.count())
    }

    private suspend fun runTasks(inventory: QueueInventory, totalNumberOfTasksToRun: Int, lastFailedTask: QueueTaskMetadata? = null) {
        if (inventory.isEmpty()) {
            logger.debug("queue done running tasks")
            return requestManager.queueRunRequestComplete()
        }

        val nextTaskToRunInventoryItem = inventory[0]
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

                // TODO parse the error to see if it was because of paused HTTP requests
                val previousRunResults = nextTaskToRun.runResults
                val newRunResults = nextTaskToRun.runResults.copy().apply { totalRuns + 1 }

                logger.debug("queue task $nextTaskStorageId, updating run history from: $previousRunResults to: $newRunResults")
                queueStorage.update(nextTaskStorageId, newRunResults)

                return goToNextTask(inventory, totalNumberOfTasksToRun, nextTaskToRunInventoryItem)
            }
        }
    }

    private suspend fun goToNextTask(inventory: QueueInventory, totalNumberOfTasksToRun: Int, lastFailedTask: QueueTaskMetadata?) {
        val newInventory = inventory.toMutableList()
        newInventory.removeFirst()
        runTasks(newInventory, totalNumberOfTasksToRun, lastFailedTask)
    }
}
