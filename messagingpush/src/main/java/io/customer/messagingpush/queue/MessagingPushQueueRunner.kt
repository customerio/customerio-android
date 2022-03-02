package io.customer.messagingpush.queue

import io.customer.messagingpush.queue.taskdata.DeletePushNotificationQueueTaskData
import io.customer.messagingpush.queue.taskdata.RegisterPushNotificationQueueTaskData
import io.customer.messagingpush.queue.type.QueueTaskType
import io.customer.sdk.api.CustomerIOAPIHttpClient
import io.customer.sdk.data.request.Device
import io.customer.sdk.extensions.valueOfOrNull
import io.customer.sdk.hooks.hooks.QueueRunnerHook
import io.customer.sdk.queue.type.QueueRunTaskResult
import io.customer.sdk.queue.type.QueueTask
import io.customer.sdk.util.JsonAdapter

class MessagingPushQueueRunner(
    private val jsonAdapter: JsonAdapter,
    private val cioHttpClient: CustomerIOAPIHttpClient
) : QueueRunnerHook {

    override suspend fun runTask(task: QueueTask): QueueRunTaskResult? {
        return when (valueOfOrNull<QueueTaskType>(task.type)) {
            null -> null
            QueueTaskType.RegisterDeviceToken -> registerDeviceToken(task)
            QueueTaskType.DeletePushToken -> deleteDeviceToken(task)
        }
    }

    private suspend fun deleteDeviceToken(task: QueueTask): QueueRunTaskResult {
        val taskData: DeletePushNotificationQueueTaskData = jsonAdapter.fromJson(task.data)

        return cioHttpClient.deleteDevice(taskData.profileIdentified, taskData.deviceToken)
    }

    private suspend fun registerDeviceToken(task: QueueTask): QueueRunTaskResult {
        val taskData: RegisterPushNotificationQueueTaskData = jsonAdapter.fromJson(task.data)

        return cioHttpClient.registerDevice(
            taskData.profileIdentified,
            Device(
                token = taskData.deviceToken,
                lastUsed = taskData.lastUsed
            )
        )
    }
}
