package io.customer.sdk.queue.taskdata

import java.util.*
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
internal data class RegisterPushNotificationQueueTaskData(
    val profileIdentified: String,
    val deviceToken: String,
    val lastUsed: Date
)
