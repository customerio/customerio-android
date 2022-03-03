package io.customer.messagingpush.api

import io.customer.messagingpush.data.request.Metric
import io.customer.messagingpush.data.request.MetricEvent
import io.customer.messagingpush.queue.taskdata.DeletePushNotificationQueueTaskData
import io.customer.messagingpush.queue.taskdata.RegisterPushNotificationQueueTaskData
import io.customer.messagingpush.queue.type.QueueTaskType
import io.customer.sdk.queue.Queue
import io.customer.sdk.repository.PreferenceRepository
import io.customer.sdk.util.DateUtil
import io.customer.sdk.util.Logger

internal interface MessagingPushApi {
    fun registerDeviceToken(deviceToken: String)
    fun deleteDeviceToken()
    fun trackMetric(
        deliveryID: String,
        event: MetricEvent,
        deviceToken: String
    )
}

internal class MessagingPushApiImpl(
    private val logger: Logger,
    private val preferenceRepository: PreferenceRepository,
    private val backgroundQueue: Queue,
    private val dateUtil: DateUtil
) : MessagingPushApi {

    override fun registerDeviceToken(deviceToken: String) {
        logger.info("registering device token $deviceToken")

        logger.debug("storing device token to device storage $deviceToken")
        preferenceRepository.saveDeviceToken(deviceToken)

        val identifiedProfileId = preferenceRepository.getIdentifier()
        if (identifiedProfileId == null) {
            logger.info("no profile identified, so not registering device token to a profile")
            return
        }

        backgroundQueue.addTask(QueueTaskType.RegisterDeviceToken, RegisterPushNotificationQueueTaskData(identifiedProfileId, deviceToken, dateUtil.now))
        // TODO grouping
        /**
         *                                     groupStart: .registeredPushToken(token: deviceToken),
         blockingGroups: [.identifiedProfile(identifier: identifier)])
         */
    }

    override fun deleteDeviceToken() {
        logger.info("deleting device token request made")

        val existingDeviceToken = preferenceRepository.getDeviceToken()
        if (existingDeviceToken == null) {
            logger.info("no device token exists so ignoring request to delete")
            return
        }

        // Do not delete push token from device storage. The token is valid
        // once given to SDK. We need it for future profile identifications.

        val identifiedProfileId = preferenceRepository.getIdentifier()
        if (identifiedProfileId == null) {
            logger.info("no profile identified so not removing device token from profile")
            return
        }

        backgroundQueue.addTask(QueueTaskType.DeletePushToken, DeletePushNotificationQueueTaskData(identifiedProfileId, existingDeviceToken))
        // TODO add groups
        /**
         *                                     blockingGroups: [
         .registeredPushToken(token: existingDeviceToken),
         .identifiedProfile(identifier: identifiedProfileId)
         ])
         */
    }

    override fun trackMetric(
        deliveryID: String,
        event: MetricEvent,
        deviceToken: String
    ) {
        logger.info("push metric ${event.name}")
        logger.debug("delivery id $deliveryID device token $deviceToken")

        backgroundQueue.addTask(
            QueueTaskType.TrackPushMetric,
            Metric(
                deliveryID = deliveryID,
                deviceToken = deviceToken,
                event = event,
                timestamp = dateUtil.now
            )
        )
    }
}
