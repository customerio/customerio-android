package io.customer.sdk.queue.type

data class QueueStatus(
    val siteId: String,
    val numTasksInQueue: Int
)
