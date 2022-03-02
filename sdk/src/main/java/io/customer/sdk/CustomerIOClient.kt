package io.customer.sdk

import io.customer.sdk.api.CustomerIOApi
import io.customer.sdk.data.model.EventType
import io.customer.sdk.data.request.Event
import io.customer.sdk.data.request.Metric
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.hooks.HooksManager
import io.customer.sdk.queue.Queue
import io.customer.sdk.queue.taskdata.IdentifyProfileQueueTaskData
import io.customer.sdk.queue.taskdata.TrackEventQueueTaskData
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
    private val preferenceRepository: PreferenceRepository,
    private val backgroundQueue: Queue,
    private val hooks: HooksManager,
    private val dateUtil: DateUtil,
    private val logger: Logger
) : CustomerIOApi {

    override fun identify(identifier: String, attributes: Map<String, Any>) {
        logger.info("identify profile $identifier")
        logger.debug("identify profile $identifier, $attributes")

        val currentlyIdentifiedProfileIdentifier = preferenceRepository.getIdentifier()
        val isChangingIdentifiedProfile = currentlyIdentifiedProfileIdentifier != null && currentlyIdentifiedProfileIdentifier != identifier
        val isFirstTimeIdentifying = currentlyIdentifiedProfileIdentifier == null

        currentlyIdentifiedProfileIdentifier?.let { currentlyIdentifiedProfileIdentifier ->
            if (isChangingIdentifiedProfile) {
                logger.info("changing profile from id $currentlyIdentifiedProfileIdentifier to $identifier")

                logger.debug("running hooks changing profile from $currentlyIdentifiedProfileIdentifier to $identifier")
                hooks.profileIdentifiedHooks.forEach { hook ->
                    hook.beforeIdentifiedProfileChange(
                        oldIdentifier = currentlyIdentifiedProfileIdentifier,
                        newIdentifier = identifier
                    )
                }
            }
        }

        val queueStatus = backgroundQueue.addTask(QueueTaskType.IdentifyProfile, IdentifyProfileQueueTaskData(identifier, attributes))

        // don't modify the state of the SDK until we confirm we added a queue task successfully.
        if (!queueStatus.success) {
            logger.debug("failed to add identify task to queue")
            return
        }

        logger.debug("storing identifier on device storage $identifier")
        preferenceRepository.saveIdentifier(identifier)

        if (isFirstTimeIdentifying || isChangingIdentifiedProfile) {
            logger.debug("running hooks profile identified")
            hooks.profileIdentifiedHooks.forEach { hook ->
                hook.profileIdentified(identifier)
            }
        }
    }

    override fun track(name: String, attributes: Map<String, Any>) {
        return track(EventType.event, name, attributes)
    }

    fun track(eventType: EventType, name: String, attributes: Map<String, Any>) {
        val eventTypeDescription = if (eventType == EventType.screen) "track screen view event" else "track event"

        logger.info("$eventTypeDescription $name")
        logger.debug("$eventTypeDescription $name attributes: $attributes")

        val identifier = preferenceRepository.getIdentifier()
        if (identifier == null) {
            // when we have anonymous profiles implemented in the SDK, we can decide to not
            // ignore events when a profile is not logged in yet.
            logger.info("ignoring $eventTypeDescription $name because no profile currently identified")
            return
        }

        backgroundQueue.addTask(QueueTaskType.TrackEvent, TrackEventQueueTaskData(name, Event(name, eventType, attributes, dateUtil.nowUnixTimestamp)))
    }

    override fun clearIdentify() {
        val identifier = preferenceRepository.getIdentifier()
        identifier?.let {
            preferenceRepository.removeIdentifier(it)
        }
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

    override fun screen(name: String, attributes: Map<String, Any>) {
        return track(EventType.screen, name, attributes)
    }
}
