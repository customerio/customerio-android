package io.customer.tracking.migration.taskdata

internal data class DeletePushNotificationQueueTaskData(
    val profileIdentified: String,
    val deviceToken: String
)
