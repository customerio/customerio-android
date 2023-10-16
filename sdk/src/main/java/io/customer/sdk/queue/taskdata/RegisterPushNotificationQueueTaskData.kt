package io.customer.sdk.queue.taskdata

import io.customer.sdk.data.request.Device
import kotlinx.serialization.Serializable

@Serializable
internal data class RegisterPushNotificationQueueTaskData(
    val profileIdentified: String,
    val device: Device
)
