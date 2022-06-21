package io.customer.sdk.queue.type

import com.squareup.moshi.JsonClass
import io.customer.sdk.extensions.random

@JsonClass(generateAdapter = true)
data class QueueTask(
    val storageId: String,
    val type: String,
    val data: String,
    val runResults: QueueTaskRunResults
) {
    companion object {
        val random: QueueTask
            get() = QueueTask(String.random, String.random, String.random, QueueTaskRunResults(0))
    }
}
