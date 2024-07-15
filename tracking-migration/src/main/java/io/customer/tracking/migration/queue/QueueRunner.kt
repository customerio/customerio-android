package io.customer.tracking.migration.queue

import io.customer.sdk.core.util.Logger
import io.customer.tracking.migration.MigrationProcessor
import io.customer.tracking.migration.util.JsonAdapter

interface QueueRunner {
    suspend fun runTask(task: QueueTask): QueueRunTaskResult
}

internal class QueueRunnerImpl(
    private val jsonAdapter: JsonAdapter,
    private val logger: Logger,
    private val migrationProcessor: MigrationProcessor
) : QueueRunner {
    override suspend fun runTask(task: QueueTask): QueueRunTaskResult {
        logger.debug("migrating task: $task")

        return jsonAdapter.parseMigrationTask(task).fold(
            onSuccess = { result ->
                migrationProcessor.processTask(result)
            },
            onFailure = { ex ->
                val errorMessage = ex.message ?: "Queue task data is invalid for $task. Could not run task."
                logger.error(errorMessage)
                Result.failure(RuntimeException(errorMessage))
            }
        )
    }
}
