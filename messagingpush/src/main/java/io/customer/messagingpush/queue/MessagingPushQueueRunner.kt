package io.customer.messagingpush.queue

import io.customer.sdk.hooks.hooks.QueueRunnerHook
import io.customer.sdk.queue.type.QueueRunTaskResult
import io.customer.sdk.queue.type.QueueTask

class MessagingPushQueueRunner : QueueRunnerHook {

    override suspend fun runTask(queueTask: QueueTask): QueueRunTaskResult? {
        return null
    }
}
