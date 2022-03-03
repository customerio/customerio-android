package io.customer.messagingpush.queue.taskdata

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
internal data class DeletePushNotificationQueueTaskData(
    val profileIdentified: String,
    val deviceToken: String
)
