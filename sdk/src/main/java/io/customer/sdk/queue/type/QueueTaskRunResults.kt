package io.customer.sdk.queue.type

import kotlinx.serialization.Serializable

/**
 * Metadata about a task in the background queue and it's execution history in the queue. Used, for example, to see how many times a task has failed running in the queue.
 */
@Serializable
data class QueueTaskRunResults(
    val totalRuns: Int
)
