package io.customer.tracking.migration.queue

import io.customer.sdk.core.util.Logger
import io.customer.tracking.migration.type.QueueRunTaskResult
import io.customer.tracking.migration.type.QueueTask
import io.customer.tracking.migration.type.QueueTaskType
import java.io.IOException

interface QueueRunner {
    suspend fun runTask(task: QueueTask): QueueRunTaskResult
}

inline fun <reified T : Enum<T>> valueOfOrNull(type: String): T? {
    return try {
        java.lang.Enum.valueOf(T::class.java, type)
    } catch (e: Exception) {
        null
    }
}

internal class QueueRunnerImpl(
//    private val jsonAdapter: JsonAdapter,
    private val logger: Logger
) : QueueRunner {
    override suspend fun runTask(task: QueueTask): QueueRunTaskResult {
        val taskResult = when (valueOfOrNull<QueueTaskType>(task.type)) {
            QueueTaskType.IdentifyProfile -> identifyProfile(task)
            QueueTaskType.TrackEvent -> trackEvent(task)
            QueueTaskType.RegisterDeviceToken -> registerDeviceToken(task)
            QueueTaskType.DeletePushToken -> deleteDeviceToken(task)
            QueueTaskType.TrackPushMetric -> trackPushMetrics(task)
            QueueTaskType.TrackDeliveryEvent -> trackDeliveryEvents(task)
            null -> null
        }
        return if (taskResult != null) {
            taskResult
        } else {
            val errorMessage =
                "Queue task ${task.type} could not find an enum to map to. Could not run task."
            logger.error(errorMessage)
            Result.failure(RuntimeException(errorMessage))
        }
    }

    private suspend fun identifyProfile(task: QueueTask): QueueRunTaskResult? {
//        val taskData: IdentifyProfileQueueTaskData =
//            jsonAdapter.fromJsonOrNull(task.data) ?: return null
//
//        return cioHttpClient.identifyProfile(taskData.identifier, taskData.attributes)
        return null
    }

    private suspend fun trackEvent(task: QueueTask): QueueRunTaskResult? {
//        val taskData: TrackEventQueueTaskData = jsonAdapter.fromJsonOrNull(task.data) ?: return null
//
//        return cioHttpClient.track(taskData.identifier, taskData.event)
        return null
    }

    private suspend fun deleteDeviceToken(task: QueueTask): QueueRunTaskResult? {
//        val taskData: DeletePushNotificationQueueTaskData =
//            jsonAdapter.fromJsonOrNull(task.data) ?: return null
//
//        return cioHttpClient.deleteDevice(taskData.profileIdentified, taskData.deviceToken)
        return null
    }

    private suspend fun registerDeviceToken(task: QueueTask): QueueRunTaskResult? {
//        val taskData: RegisterPushNotificationQueueTaskData =
//            jsonAdapter.fromJsonOrNull(task.data) ?: return null
//
//        return cioHttpClient.registerDevice(
//            taskData.profileIdentified,
//            taskData.device
//        )
        return null
    }

    private suspend fun trackPushMetrics(task: QueueTask): QueueRunTaskResult? {
//        val taskData: Metric = jsonAdapter.fromJsonOrNull(task.data) ?: return null
//
//        return cioHttpClient.trackPushMetrics(taskData)
        return null
    }

    @Throws(IOException::class, RuntimeException::class)
    private suspend fun trackDeliveryEvents(task: QueueTask): QueueRunTaskResult? {
//        val taskData: DeliveryEvent = jsonAdapter.fromJsonOrNull(task.data) ?: return null
//
//        return cioHttpClient.trackDeliveryEvents(taskData)
        return null
    }
}
