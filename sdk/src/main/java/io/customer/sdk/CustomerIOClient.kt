package io.customer.sdk

import io.customer.sdk.api.CustomerIOApi
import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.data.model.EventType
import io.customer.sdk.data.model.verify
import io.customer.sdk.data.request.Device
import io.customer.sdk.data.request.Event
import io.customer.sdk.data.request.Metric
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.data.store.DeviceStore
import io.customer.sdk.queue.Queue
import io.customer.sdk.queue.taskdata.DeletePushNotificationQueueTaskData
import io.customer.sdk.queue.taskdata.IdentifyProfileQueueTaskData
import io.customer.sdk.queue.taskdata.RegisterPushNotificationQueueTaskData
import io.customer.sdk.queue.taskdata.TrackEventQueueTaskData
import io.customer.sdk.queue.type.QueueTaskGroup
import io.customer.sdk.queue.type.QueueTaskType
import io.customer.sdk.repository.PreferenceRepository
import io.customer.sdk.util.DateUtil
import io.customer.sdk.util.Logger
import java.util.*

/**
 * CustomerIoClient is client class to hold all repositories and act as a bridge between
 * repositories and `CustomerIo` class
 */
internal class CustomerIOClient(
    private val config: CustomerIOConfig,
    private val deviceStore: DeviceStore,
    private val preferenceRepository: PreferenceRepository,
    private val backgroundQueue: Queue,
    private val dateUtil: DateUtil,
    private val logger: Logger
) : CustomerIOApi {

    override fun identify(identifier: String, attributes: CustomAttributes) {
        logger.info("identify profile $identifier")
        logger.debug("identify profile $identifier, $attributes")

        val currentlyIdentifiedProfileIdentifier = preferenceRepository.getIdentifier()
        // The SDK calls identify() with the already identified profile for changing profile attributes.
        val isChangingIdentifiedProfile = currentlyIdentifiedProfileIdentifier != null && currentlyIdentifiedProfileIdentifier != identifier
        val isFirstTimeIdentifying = currentlyIdentifiedProfileIdentifier == null

        currentlyIdentifiedProfileIdentifier?.let { currentlyIdentifiedProfileIdentifier ->
            if (isChangingIdentifiedProfile) {
                logger.info("changing profile from id $currentlyIdentifiedProfileIdentifier to $identifier")

                logger.debug("deleting device token before identifying new profile")
                this.deleteDeviceToken()
            }
        }

        // If SDK previously identified profile X and X is being identified again, no use blocking the queue with a queue group.
        val queueGroupStart = if (isFirstTimeIdentifying || isChangingIdentifiedProfile) QueueTaskGroup.IdentifyProfile(identifier) else null
        // If there was a previously identified profile, or, we are just adding attributes to an existing profile, we need to block
        // this operation until the previous identify runs successfully.
        val blockingGroups = if (currentlyIdentifiedProfileIdentifier != null) listOf(QueueTaskGroup.IdentifyProfile(currentlyIdentifiedProfileIdentifier)) else null

        val queueStatus = backgroundQueue.addTask(
            QueueTaskType.IdentifyProfile,
            IdentifyProfileQueueTaskData(identifier, attributes),
            groupStart = queueGroupStart,
            blockingGroups = blockingGroups
        )

        // don't modify the state of the SDK until we confirm we added a queue task successfully.
        if (!queueStatus.success) {
            logger.debug("failed to add identify task to queue")
            return
        }

        logger.debug("storing identifier on device storage $identifier")
        preferenceRepository.saveIdentifier(identifier)

        if (isFirstTimeIdentifying || isChangingIdentifiedProfile) {
            logger.debug("first time identified or changing identified profile")

            preferenceRepository.getDeviceToken()?.let {
                logger.debug("automatically registering device token to newly identified profile")
                registerDeviceToken(it, emptyMap()) // no new attributes but default ones to pass so pass empty.
            }
        }
    }

    override fun track(name: String, attributes: CustomAttributes) {
        return track(EventType.event, name, attributes)
    }

    fun track(eventType: EventType, name: String, attributes: CustomAttributes) {
        val eventTypeDescription = if (eventType == EventType.screen) "track screen view event" else "track event"

        logger.info("$eventTypeDescription $name")
        logger.debug("$eventTypeDescription $name attributes: $attributes")

        // Clean-up attributes before any JSON parsing.
        // TODO implement implementation tests for background queue and provide invalid attributes to make sure that we remembered to call this function.
        val attributes = attributes.verify()

        val identifier = preferenceRepository.getIdentifier()
        if (identifier == null) {
            // when we have anonymous profiles implemented in the SDK, we can decide to not
            // ignore events when a profile is not logged in yet.
            logger.info("ignoring $eventTypeDescription $name because no profile currently identified")
            return
        }

        backgroundQueue.addTask(
            QueueTaskType.TrackEvent,
            TrackEventQueueTaskData(name, Event(name, eventType, attributes, dateUtil.nowUnixTimestamp)),
            blockingGroups = listOf(QueueTaskGroup.IdentifyProfile(identifier))
        )
    }

    override fun clearIdentify() {
        preferenceRepository.getIdentifier()?.let { identifier ->
            preferenceRepository.removeIdentifier(identifier)
        }
    }

    override fun screen(name: String, attributes: CustomAttributes) {
        return track(EventType.screen, name, attributes)
    }

    override fun registerDeviceToken(deviceToken: String, attributes: CustomAttributes) {
        val attributes = createDeviceAttributes(attributes)

        logger.info("registering device token $deviceToken, attributes: $attributes")

        logger.debug("storing device token to device storage $deviceToken")
        preferenceRepository.saveDeviceToken(deviceToken)

        val identifiedProfileId = preferenceRepository.getIdentifier()
        if (identifiedProfileId == null) {
            logger.info("no profile identified, so not registering device token to a profile")
            return
        }

        val device = Device(
            token = deviceToken,
            lastUsed = dateUtil.now,
            attributes = attributes
        )

        backgroundQueue.addTask(
            QueueTaskType.RegisterDeviceToken,
            RegisterPushNotificationQueueTaskData(identifiedProfileId, device),
            groupStart = QueueTaskGroup.RegisterPushToken(deviceToken),
            blockingGroups = listOf(QueueTaskGroup.IdentifyProfile(identifiedProfileId))
        )
    }

    private fun createDeviceAttributes(customAddedAttributes: CustomAttributes): Map<String, Any> {
        if (!config.autoTrackDeviceAttributes) return customAddedAttributes

        val defaultAttributes = deviceStore.buildDeviceAttributes()

        return defaultAttributes + customAddedAttributes // order matters! allow customer to override default values if they wish.
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

        backgroundQueue.addTask(
            QueueTaskType.DeletePushToken,
            DeletePushNotificationQueueTaskData(identifiedProfileId, existingDeviceToken),
            // only delete a device token after it has successfully been registered.
            blockingGroups = listOf(QueueTaskGroup.RegisterPushToken(existingDeviceToken))
        )
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
            ),
            blockingGroups = listOf(QueueTaskGroup.RegisterPushToken(deviceToken))
        )
    }
}
