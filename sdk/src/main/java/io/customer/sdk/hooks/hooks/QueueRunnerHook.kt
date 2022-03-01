package io.customer.sdk.hooks.hooks

import io.customer.sdk.queue.type.QueueRunTaskResult
import io.customer.sdk.queue.type.QueueTask

// When a module wants to run background queue tasks, they implement this hook.
interface QueueRunnerHook {
    // / called from background queue in `Tracking` module.
    // / if queue task does *not* belong to the module, return null and the queue will send the task to another module.
    suspend fun runTask(queueTask: QueueTask): QueueRunTaskResult?
}
