package io.customer.sdk.queue

import io.customer.sdk.queue.type.QueueTaskMetadata
import io.customer.sdk.util.Logger

interface QueueQueryRunner {
    fun getNextTask(queue: List<QueueTaskMetadata>, lastFailedTask: QueueTaskMetadata?): QueueTaskMetadata?
    fun reset()
}

internal class QueueQueryRunnerImpl(
    private val logger: Logger
) : QueueQueryRunner {
    internal val queryCriteria = QueueQueryCriteria()

    override fun getNextTask(queue: List<QueueTaskMetadata>, lastFailedTask: QueueTaskMetadata?): QueueTaskMetadata? {
        if (queue.isEmpty()) return null
        if (lastFailedTask != null) updateCriteria(lastFailedTask)

        // log *after* updating the criteria
        logger.debug("queue querying next task. criteria: $queryCriteria")

        return queue.firstOrNull { doesTaskPassCriteria(it) }
    }

    internal fun updateCriteria(lastFailedTask: QueueTaskMetadata) {
        lastFailedTask.groupStart?.let { queueGroupName ->
            queryCriteria.excludeGroups.add(queueGroupName)
        }
    }

    private fun doesTaskPassCriteria(task: QueueTaskMetadata): Boolean {
        // At this time, function only contains 1 query criteria. If more were added in the future, we can chain them together:
        // queryCriteria.doesTaskX() ?: queryCriteria.doesTaskY()
        return !doesTaskBelongToExcludedGroup(task)
    }

    private fun doesTaskBelongToExcludedGroup(task: QueueTaskMetadata): Boolean {
        task.groupMember?.let { groupsTaskBelongsTo ->
            queryCriteria.excludeGroups.forEach { groupToExclude ->
                if (groupsTaskBelongsTo.contains(groupToExclude)) {
                    return true
                }
            }
        }

        return false
    }

    override fun reset() {
        logger.debug("resetting queue tasks query criteria")

        queryCriteria.reset()
    }

    internal data class QueueQueryCriteria(
        val excludeGroups: MutableSet<String> = mutableSetOf()
    ) {
        fun reset() {
            excludeGroups.clear()
        }
    }
}
