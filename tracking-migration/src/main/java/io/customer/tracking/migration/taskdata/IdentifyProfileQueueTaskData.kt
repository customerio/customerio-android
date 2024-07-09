package io.customer.tracking.migration.taskdata

data class IdentifyProfileQueueTaskData(
    val identifier: String,
    val attributes: Map<String, Any>
)
