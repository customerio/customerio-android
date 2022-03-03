package io.customer.messagingpush.queue

import io.customer.messagingpush.api.MessagingPushHttpClient
import io.customer.messagingpush.data.request.Device
import io.customer.messagingpush.data.request.Metric
import io.customer.messagingpush.queue.taskdata.DeletePushNotificationQueueTaskData
import io.customer.messagingpush.queue.taskdata.RegisterPushNotificationQueueTaskData
import io.customer.messagingpush.queue.type.QueueTaskType
import io.customer.sdk.extensions.valueOfOrNull
import io.customer.sdk.hooks.hooks.QueueRunnerHook
import io.customer.sdk.queue.type.QueueRunTaskResult
import io.customer.sdk.queue.type.QueueTask
import io.customer.sdk.util.JsonAdapter

internal class MessagingPushQueueRunner(
    private val jsonAdapter: JsonAdapter,
    private val httpClient: MessagingPushHttpClient
) : QueueRunnerHook {

    override suspend fun runTask(task: QueueTask): QueueRunTaskResult? {
        return when (valueOfOrNull<QueueTaskType>(task.type)) {
            null -> null
            QueueTaskType.RegisterDeviceToken -> registerDeviceToken(task)
            QueueTaskType.DeletePushToken -> deleteDeviceToken(task)
            QueueTaskType.TrackPushMetric -> trackPushMetrics(task)
        }
    }

    private suspend fun deleteDeviceToken(task: QueueTask): QueueRunTaskResult {
        val taskData: DeletePushNotificationQueueTaskData = jsonAdapter.fromJson(task.data)

        return httpClient.deleteDevice(taskData.profileIdentified, taskData.deviceToken)
    }

    private suspend fun registerDeviceToken(task: QueueTask): QueueRunTaskResult {
        val taskData: RegisterPushNotificationQueueTaskData = jsonAdapter.fromJson(task.data)

        return httpClient.registerDevice(
            taskData.profileIdentified,
            Device(
                token = taskData.deviceToken,
                lastUsed = taskData.lastUsed
            )
        )
    }

    private suspend fun trackPushMetrics(task: QueueTask): QueueRunTaskResult {
        val taskData: Metric = jsonAdapter.fromJson(task.data)

        return httpClient.trackPushMetrics(taskData)
    }
}
