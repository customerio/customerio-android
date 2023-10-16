package io.customer.sdk.queue.taskdata

import kotlinx.serialization.Serializable

@Serializable
internal data class DeletePushNotificationQueueTaskData(
    val profileIdentified: String,
    val deviceToken: String
)
