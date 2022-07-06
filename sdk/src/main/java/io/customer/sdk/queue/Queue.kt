package io.customer.sdk.queue

import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.data.model.EventType
import io.customer.sdk.data.request.Device
import io.customer.sdk.data.request.Event
import io.customer.sdk.data.request.Metric
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.queue.taskdata.DeletePushNotificationQueueTaskData
import io.customer.sdk.queue.taskdata.IdentifyProfileQueueTaskData
import io.customer.sdk.queue.taskdata.RegisterPushNotificationQueueTaskData
import io.customer.sdk.queue.taskdata.TrackEventQueueTaskData
import io.customer.sdk.queue.type.QueueModifyResult
import io.customer.sdk.queue.type.QueueStatus
import io.customer.sdk.queue.type.QueueTaskGroup
import io.customer.sdk.queue.type.QueueTaskType
import io.customer.sdk.util.DateUtil
import io.customer.sdk.util.DispatchersProvider
import io.customer.sdk.util.JsonAdapter
import io.customer.sdk.util.Logger
import io.customer.sdk.util.Seconds
import io.customer.sdk.util.SimpleTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

interface Queue {
    fun queueIdentifyProfile(newIdentifier: String, oldIdentifier: String?, attributes: CustomAttributes): QueueModifyResult
    fun queueTrack(identifiedProfileId: String, name: String, eventType: EventType, attributes: CustomAttributes): QueueModifyResult
    fun queueRegisterDevice(identifiedProfileId: String, device: Device): QueueModifyResult
    fun queueDeletePushToken(identifiedProfileId: String, deviceToken: String): QueueModifyResult
    fun queueTrackMetric(deliveryId: String, deviceToken: String, event: MetricEvent): QueueModifyResult

    fun <TaskType : Enum<*>, TaskData : Any> addTask(
        type: TaskType,
        data: TaskData,
        groupStart: QueueTaskGroup? = null,
        blockingGroups: List<QueueTaskGroup>? = null
    ): QueueModifyResult

    suspend fun run()

    fun deleteExpiredTasks()
}

internal class QueueImpl internal constructor(
    private val dispatchersProvider: DispatchersProvider,
    private val storage: QueueStorage,
    private val runRequest: QueueRunRequest,
    private val jsonAdapter: JsonAdapter,
    private val sdkConfig: CustomerIOConfig,
    private val queueTimer: SimpleTimer,
    private val logger: Logger,
    private val dateUtil: DateUtil
) : Queue {

    private val numberSecondsToScheduleTimer: Seconds
        get() = Seconds(sdkConfig.backgroundQueueSecondsDelay)

    @Volatile var isRunningRequest: Boolean = false

    override fun <TaskType : Enum<*>, TaskData : Any> addTask(
        type: TaskType,
        data: TaskData,
        groupStart: QueueTaskGroup?,
        blockingGroups: List<QueueTaskGroup>?
    ): QueueModifyResult {
        synchronized(this) {
            logger.info("adding queue task $type")

            val taskDataString = jsonAdapter.toJson(data)

            // What do we do if a queue task doesn't successfully get added to the queue?
            //
            val createTaskResult = storage.create(
                type = type.name,
                data = taskDataString,
                groupStart = groupStart,
                blockingGroups = blockingGroups
            )
            logger.debug("added queue task data $taskDataString")

            processQueueStatus(createTaskResult.queueStatus)

            return createTaskResult
        }
    }

    override suspend fun run() {
        synchronized(this) {
            queueTimer.cancel()

            val isQueueRunningRequest = isRunningRequest
            if (isQueueRunningRequest) return

            isRunningRequest = true
        }

        runRequest.run()

        synchronized(this) {
            // reset queue to be able to run again
            isRunningRequest = false
        }
    }

    internal fun runAsync() {
        CoroutineScope(dispatchersProvider.background).launch {
            run()
        }
    }

    private fun processQueueStatus(queueStatus: QueueStatus) {
        logger.debug("processing queue status $queueStatus")
        val isManyTasksInQueue = queueStatus.numTasksInQueue >= sdkConfig.backgroundQueueMinNumberOfTasks

        if (isManyTasksInQueue) {
            logger.info("queue met criteria to run automatically")

            runAsync()
        } else {
            // Not enough tasks in the queue yet to run it now, so let's schedule them to run in the future.
            // It's expected that only 1 timer instance exists and is running in the SDK.
            val didSchedule = queueTimer.scheduleIfNotAlready(numberSecondsToScheduleTimer) {
                logger.info("queue timer: now running queue")

                runAsync()
            }

            if (didSchedule) logger.info("queue timer: scheduled to run queue in $numberSecondsToScheduleTimer seconds")
        }
    }

    override fun queueRegisterDevice(
        identifiedProfileId: String,
        device: Device
    ): QueueModifyResult {
        return addTask(
            QueueTaskType.RegisterDeviceToken,
            RegisterPushNotificationQueueTaskData(identifiedProfileId, device),
            groupStart = QueueTaskGroup.RegisterPushToken(device.token),
            blockingGroups = listOf(QueueTaskGroup.IdentifyProfile(identifiedProfileId))
        )
    }

    override fun queueDeletePushToken(identifiedProfileId: String, deviceToken: String): QueueModifyResult {
        return addTask(
            QueueTaskType.DeletePushToken,
            DeletePushNotificationQueueTaskData(identifiedProfileId, deviceToken),
            // only delete a device token after it has successfully been registered.
            blockingGroups = listOf(QueueTaskGroup.RegisterPushToken(deviceToken))
        )
    }

    override fun queueTrack(
        identifiedProfileId: String,
        name: String,
        eventType: EventType,
        attributes: CustomAttributes
    ): QueueModifyResult {
        val event = Event(
            name = name,
            type = eventType,
            data = attributes,
            timestamp = dateUtil.nowUnixTimestamp
        )

        return addTask(
            QueueTaskType.TrackEvent,
            TrackEventQueueTaskData(identifiedProfileId, event),
            blockingGroups = listOf(QueueTaskGroup.IdentifyProfile(identifiedProfileId))
        )
    }

    override fun queueTrackMetric(
        deliveryId: String,
        deviceToken: String,
        event: MetricEvent
    ): QueueModifyResult {
        return addTask(
            QueueTaskType.TrackPushMetric,
            Metric(
                deliveryID = deliveryId,
                deviceToken = deviceToken,
                event = event,
                timestamp = dateUtil.now
            ),
            blockingGroups = listOf(QueueTaskGroup.RegisterPushToken(deviceToken))
        )
    }

    override fun queueIdentifyProfile(
        newIdentifier: String,
        oldIdentifier: String?,
        attributes: CustomAttributes
    ): QueueModifyResult {
        val isFirstTimeIdentifying = oldIdentifier == null
        val isChangingIdentifiedProfile = oldIdentifier != null && oldIdentifier != newIdentifier

        // If SDK previously identified profile X and X is being identified again, no use blocking the queue with a queue group.
        val queueGroupStart = if (isFirstTimeIdentifying || isChangingIdentifiedProfile) QueueTaskGroup.IdentifyProfile(newIdentifier) else null
        // If there was a previously identified profile, or, we are just adding attributes to an existing profile, we need to wait for
        // this operation until the previous identify runs successfully.
        val blockingGroups = if (!isFirstTimeIdentifying) listOf(QueueTaskGroup.IdentifyProfile(oldIdentifier!!)) else null

        return addTask(
            QueueTaskType.IdentifyProfile,
            IdentifyProfileQueueTaskData(newIdentifier, attributes),
            groupStart = queueGroupStart,
            blockingGroups = blockingGroups
        )
    }

    override fun deleteExpiredTasks() {
        storage.deleteExpired()
    }
}
