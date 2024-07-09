package io.customer.tracking.migration.type

data class QueueStatus(
    val siteId: String,
    val numTasksInQueue: Int
)
