package io.customer.sdk.queue

import io.customer.sdk.queue.type.QueueTaskMetadata

interface QueueQueryRunner {
    fun getNextTask(queue: List<QueueTaskMetadata>, lastFailedTask: QueueTaskMetadata?): QueueTaskMetadata?
}

class QueueQueryRunnerImpl : QueueQueryRunner {
    private val queryCriteria = QueueQueryCriteria()

    override fun getNextTask(queue: List<QueueTaskMetadata>, lastFailedTask: QueueTaskMetadata?): QueueTaskMetadata? {
        if (queue.isEmpty()) return null
        if (lastFailedTask == null) return queue[0]

        updateCriteria(lastFailedTask)

        return queue.first { doesTaskPassCriteria(it) }
    }

    private fun updateCriteria(lastFailedTask: QueueTaskMetadata) {
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

    private data class QueueQueryCriteria(
        val excludeGroups: MutableSet<String> = mutableSetOf()
    )
}
