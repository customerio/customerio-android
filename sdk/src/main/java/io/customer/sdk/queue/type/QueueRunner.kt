package io.customer.sdk.queue.type

import io.customer.sdk.api.CustomerIOAPIHttpClient
import io.customer.sdk.extensions.valueOfOrNull
import io.customer.sdk.queue.taskdata.TrackEventQueueTaskData
import io.customer.sdk.util.JsonAdapter

interface QueueRunner {
    suspend fun runTask(task: QueueTask): QueueRunTaskResult
}

internal class QueueRunnerImpl(
    private val jsonAdapter: JsonAdapter,
    private val cioHttpClient: CustomerIOAPIHttpClient
) : QueueRunner {
    override suspend fun runTask(task: QueueTask): QueueRunTaskResult {
        when (valueOfOrNull<QueueTaskType>(task.type)) {
            QueueTaskType.IdentifyProfile -> TODO() // return identifyProfile(task)
            QueueTaskType.TrackEvent -> return trackEvent(task)
            null -> {
                // TODO where hooks will attempt to run queue task in other module.
            }
        }

        return Result.success(Unit)
    }

//    private suspend fun identifyProfile(task: QueueTask): QueueRunTaskResult {
//        val taskData: IdentifyProfileQueueTaskData = jsonAdapter.fromJson(task.data)
//
//        // TODO perform http request
//    }

    private suspend fun trackEvent(task: QueueTask): QueueRunTaskResult {
        val taskData: TrackEventQueueTaskData = jsonAdapter.fromJson(task.data)

        return cioHttpClient.track(taskData.identifier, taskData.event)
    }
}
