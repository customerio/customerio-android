package io.customer.sdk.queue.taskdata

import io.customer.sdk.data.request.Device
import java.util.*

internal data class RegisterPushNotificationQueueTaskData(
    val profileIdentified: String,
    val device: Device
)
