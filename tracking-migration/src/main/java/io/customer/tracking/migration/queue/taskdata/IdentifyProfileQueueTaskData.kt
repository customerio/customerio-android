package io.customer.sdk.queue.taskdata

data class IdentifyProfileQueueTaskData(
    val identifier: String,
    val attributes: Map<String, Any>
)
