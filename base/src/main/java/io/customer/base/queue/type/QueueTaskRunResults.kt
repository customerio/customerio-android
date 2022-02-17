package io.customer.sdk.queue.type

import com.squareup.moshi.JsonClass

/**
 * Metadata about a task in the background queue and it's execution history in the queue. Used, for example, to see how many times a task has failed running in the queue.
 */
@JsonClass(generateAdapter = true)
data class QueueTaskRunResults(
    val totalRuns: Int
)
