package io.customer.sdk.queue

import kotlinx.coroutines.runBlocking

class Queue internal constructor(
    private val storage: QueueStorage
) {

    fun addTask(type: String): QueueAddTaskResult {
        return QueueAddTaskResult(false, QueueStatus("", 3))
    }
}

data class QueueStatus(
    val siteId: String,
    val numTasksInQueue: Int
)

data class QueueAddTaskResult(
    val success: Boolean,
    val queueStatus: QueueStatus
)
