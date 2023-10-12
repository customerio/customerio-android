package io.customer.sdk.queue.taskdata

import com.squareup.moshi.JsonClass
import io.customer.sdk.data.request.Device
import java.util.*

@JsonClass(generateAdapter = true)
internal data class RegisterPushNotificationQueueTaskData(
    val profileIdentified: String,
    val device: Device
)
