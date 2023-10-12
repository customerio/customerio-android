package io.customer.sdk.queue.taskdata

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class IdentifyProfileQueueTaskData(
    val identifier: String,
    val attributes: Map<String, Any>
)
