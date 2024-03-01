package io.customer.sdk.queue

import io.customer.sdk.error.CustomerIOError
import io.customer.sdk.queue.type.QueueTaskMetadata
import io.customer.sdk.util.Logger

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

        var lastFailedTask: QueueTaskMetadata? = null

        while (tasksToRun.isNotEmpty()) {
            // get the next task to run using the query criteria
            val currentTaskMetadata = queryRunner.getNextTask(tasksToRun, lastFailedTask)
            if (currentTaskMetadata == null) {
                logger.debug("queue out of tasks to run...")
                break
            }

            tasksToRun.remove(currentTaskMetadata)

            val taskStorageId = currentTaskMetadata.taskPersistedId
            val taskToRun = queueStorage.get(taskStorageId)

            if (taskToRun == null) {
                logger.error("Tried to get queue task with storage id: $taskStorageId but storage couldn't find it.")
                // The task failed to execute because it couldn't be found. Gracefully handle the scenario by
                // behaving the same way a failed HTTP request does. Run next task with an updated `lastFailedTask`.
                lastFailedTask = currentTaskMetadata
                continue
            }

            logger.debug("queue tasks left to run: ${tasksToRun.size}")
            logger.debug("queue next task to run: $taskStorageId, ${taskToRun.type}, ${taskToRun.data}, ${taskToRun.runResults}")

            val result = runner.runTask(taskToRun)

            when {
                result.isSuccess -> {
                    logger.debug("queue task $taskStorageId ran successfully")
                    logger.debug("queue deleting task $taskStorageId")
                    queueStorage.delete(taskStorageId)
                }

                result.isFailure -> {
                    val error = result.exceptionOrNull()
                    logger.debug("queue task $taskStorageId run failed $error")

                    when (error as? CustomerIOError) {
                        is CustomerIOError.HttpRequestsPaused, is CustomerIOError.NoHttpRequestMade -> {
                            logger.info("queue is quitting early because ${error.message})")
                            tasksToRun.clear() // clear the list to stop processing the next tasks
                            break
                        }

                        is CustomerIOError.BadRequest400 -> {
                            logger.error("Received HTTP 400 response while trying to run ${taskToRun.type}. 400 responses never succeed and therefore, the SDK is deleting this SDK request and not retry. Error message from API: ${error.message}, request data sent: ${taskToRun.data}")
                            queueStorage.delete(taskStorageId)
                        }

                        else -> {
                            val previousRunResults = taskToRun.runResults
                            val newRunResults =
                                taskToRun.runResults.copy(totalRuns = previousRunResults.totalRuns + 1)
                            logger.debug("queue task $taskStorageId, updating run history from: $previousRunResults to: $newRunResults")
                            queueStorage.update(taskStorageId, newRunResults)
                        }
                    }
                    lastFailedTask = currentTaskMetadata
                }
            }
        }
        logger.debug("queue done running tasks")
        queryRunner.reset()
    }
}
