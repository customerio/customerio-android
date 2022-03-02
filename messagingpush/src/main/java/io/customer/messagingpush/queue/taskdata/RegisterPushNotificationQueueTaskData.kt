package io.customer.messagingpush.queue.taskdata

import java.util.*
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RegisterPushNotificationQueueTaskData(
    val profileIdentified: String,
    val deviceToken: String,
    val lastUsed: Date
)
