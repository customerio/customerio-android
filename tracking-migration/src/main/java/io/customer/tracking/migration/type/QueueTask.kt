package io.customer.tracking.migration.type

data class QueueTask(
    val storageId: String,
    val type: String,
    val data: String,
    val runResults: QueueTaskRunResults
)
