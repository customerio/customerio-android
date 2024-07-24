package io.customer.tracking.migration.queue

import io.customer.sdk.core.util.Logger

interface QueueQueryRunner {
    fun getNextTask(queue: List<QueueTaskMetadata>): QueueTaskMetadata?
}

internal class QueueQueryRunnerImpl(
    private val logger: Logger
) : QueueQueryRunner {

    override fun getNextTask(queue: List<QueueTaskMetadata>): QueueTaskMetadata? {
        if (queue.isEmpty()) return null

        // log *after* updating the criteria
        logger.debug("queue querying next task")

        return queue.firstOrNull()
    }
}
