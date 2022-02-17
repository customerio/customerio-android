package io.customer.sdk.queue.type

// / After performing a modification task (create, update, delete) on the queue, result of the call.
data class QueueModifyResult(
    val success: Boolean, // was the modification operation successful?
    val queueStatus: QueueStatus
)
