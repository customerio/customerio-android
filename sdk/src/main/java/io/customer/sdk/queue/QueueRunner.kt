package io.customer.sdk.queue

import io.customer.sdk.api.TrackingHttpClient
import io.customer.sdk.data.request.Device
import io.customer.sdk.data.request.Metric
import io.customer.sdk.extensions.valueOfOrNull
import io.customer.sdk.queue.taskdata.DeletePushNotificationQueueTaskData
import io.customer.sdk.queue.taskdata.IdentifyProfileQueueTaskData
import io.customer.sdk.queue.taskdata.RegisterPushNotificationQueueTaskData
import io.customer.sdk.queue.taskdata.TrackEventQueueTaskData
import io.customer.sdk.queue.type.QueueRunTaskResult
import io.customer.sdk.queue.type.QueueTask
import io.customer.sdk.queue.type.QueueTaskType
import io.customer.sdk.util.JsonAdapter
import io.customer.sdk.util.Logger

interface QueueRunner {
    suspend fun runTask(task: QueueTask): QueueRunTaskResult
}

internal class QueueRunnerImpl(
    private val jsonAdapter: JsonAdapter,
    private val cioHttpClient: TrackingHttpClient,
    private val logger: Logger
) : QueueRunner {
    override suspend fun runTask(task: QueueTask): QueueRunTaskResult {
        return when (valueOfOrNull<QueueTaskType>(task.type)) {
            QueueTaskType.IdentifyProfile -> identifyProfile(task)
            QueueTaskType.TrackEvent -> trackEvent(task)
            QueueTaskType.RegisterDeviceToken -> registerDeviceToken(task)
            QueueTaskType.DeletePushToken -> deleteDeviceToken(task)
            QueueTaskType.TrackPushMetric -> trackPushMetrics(task)
            null -> {
                val errorMessage = "Queue task ${task.type} could not find an enum to map to. Could not run task."
                logger.error(errorMessage)
                return Result.failure(RuntimeException(errorMessage))
            }
        }
    }

    private suspend fun identifyProfile(task: QueueTask): QueueRunTaskResult {
        val taskData: IdentifyProfileQueueTaskData = jsonAdapter.fromJson(task.data)

        return cioHttpClient.identifyProfile(taskData.identifier, taskData.attributes)
    }

    private suspend fun trackEvent(task: QueueTask): QueueRunTaskResult {
        val taskData: TrackEventQueueTaskData = jsonAdapter.fromJson(task.data)

        return cioHttpClient.track(taskData.identifier, taskData.event)
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

    private suspend fun trackPushMetrics(task: QueueTask): QueueRunTaskResult {
        val taskData: Metric = jsonAdapter.fromJson(task.data)

        return cioHttpClient.trackPushMetrics(taskData)
    }
}
