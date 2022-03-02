package io.customer.messagingpush.queue.type

// All the types of tasks the MessagingPush module runs in the background queue
internal enum class QueueTaskType {
    RegisterDeviceToken,
    DeletePushToken
}
