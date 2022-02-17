package io.customer.sdk.queue.type

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class QueueTask(
    val storageId: String,
    val type: String,
    val data: String,
    val runResults: QueueTaskRunResults
)
