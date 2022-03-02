package io.customer.messagingpush.queue

import io.customer.sdk.hooks.hooks.QueueRunnerHook
import io.customer.sdk.queue.type.QueueRunTaskResult
import io.customer.sdk.queue.type.QueueTask

class MessagingPushQueueRunner : QueueRunnerHook {

    override suspend fun runTask(queueTask: QueueTask): QueueRunTaskResult? {
        // TODO in future background queue task when all public SDK functions are converted to using
        // the background queue, this will be implemented to run tasks from the messaging push module.
        return null
    }
}
