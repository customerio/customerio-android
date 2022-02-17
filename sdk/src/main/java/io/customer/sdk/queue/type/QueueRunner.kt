package io.customer.sdk.queue.type

import io.customer.sdk.extensions.valueOfOrNull

interface QueueRunner {
    suspend fun runTask(task: QueueTask): Result<Unit>
}

class QueueRunnerImpl : QueueRunner {
    override suspend fun runTask(task: QueueTask): Result<Unit> {
        when (valueOfOrNull<QueueTaskType>(task.type)) {
            QueueTaskType.IdentifyProfile -> TODO()
            QueueTaskType.TrackEvent -> TODO()
            null -> {
                // TODO where hooks will attempt to run queue task in other module.
            }
        }

        return Result.success(Unit)
    }
}
