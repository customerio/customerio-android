package io.customer.sdk.queue.taskdata

internal data class DeletePushNotificationQueueTaskData(
    val profileIdentified: String,
    val deviceToken: String
)
