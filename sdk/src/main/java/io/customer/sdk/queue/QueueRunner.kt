package io.customer.sdk.queue

import io.customer.base.error.InternalSdkError
import io.customer.base.extenstions.mapFirstSuspend
import io.customer.sdk.api.TrackingHttpClient
import io.customer.sdk.extensions.valueOfOrNull
import io.customer.sdk.hooks.HooksManager
import io.customer.sdk.queue.taskdata.IdentifyProfileQueueTaskData
import io.customer.sdk.queue.taskdata.TrackEventQueueTaskData
import io.customer.sdk.queue.type.QueueRunTaskResult
import io.customer.sdk.queue.type.QueueTask
import io.customer.sdk.queue.type.QueueTaskType
import io.customer.sdk.util.JsonAdapter

interface QueueRunner {
    suspend fun runTask(task: QueueTask): QueueRunTaskResult
}

internal class QueueRunnerImpl(
    private val jsonAdapter: JsonAdapter,
    private val cioHttpClient: TrackingHttpClient,
    private val hooks: HooksManager
) : QueueRunner {
    override suspend fun runTask(task: QueueTask): QueueRunTaskResult {
        return when (valueOfOrNull<QueueTaskType>(task.type)) {
            QueueTaskType.IdentifyProfile -> identifyProfile(task)
            QueueTaskType.TrackEvent -> trackEvent(task)

            null -> {
                val runTaskResult = hooks.queueRunnerHooks.mapFirstSuspend { hook ->
                    hook.runTask(task)
                }

                runTaskResult ?: Result.failure(InternalSdkError("task ${task.type} not handled by any module"))
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
}
