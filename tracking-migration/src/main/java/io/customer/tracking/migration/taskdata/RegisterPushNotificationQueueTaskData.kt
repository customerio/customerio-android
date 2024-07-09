package io.customer.tracking.migration.taskdata

import io.customer.tracking.migration.request.Device

internal data class RegisterPushNotificationQueueTaskData(
    val profileIdentified: String,
    val device: Device
)
