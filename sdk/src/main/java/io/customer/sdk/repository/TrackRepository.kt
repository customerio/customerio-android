package io.customer.sdk.repository

import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.data.model.EventType
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.queue.Queue
import io.customer.sdk.util.Logger

interface TrackRepository {
    fun track(name: String, attributes: CustomAttributes)
    fun trackMetric(deliveryID: String, event: MetricEvent, deviceToken: String)
    fun screen(name: String, attributes: CustomAttributes)
}

internal class TrackRepositoryImpl(
    private val preferenceRepository: PreferenceRepository,
    private val backgroundQueue: Queue,
    private val logger: Logger
) : TrackRepository {

    override fun track(name: String, attributes: CustomAttributes) {
        return track(EventType.event, name, attributes)
    }

    override fun screen(name: String, attributes: CustomAttributes) {
        return track(EventType.screen, name, attributes)
    }

    private fun track(eventType: EventType, name: String, attributes: CustomAttributes) {
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

        // if task doesn't successfully get added to the queue, it does not break the SDK's state. So, we can ignore the result of adding task to queue.
        backgroundQueue.queueTrack(identifier, name, eventType, attributes)
    }

    override fun trackMetric(
        deliveryID: String,
        event: MetricEvent,
        deviceToken: String
    ) {
        logger.info("push metric ${event.name}")
        logger.debug("delivery id $deliveryID device token $deviceToken")

        // if task doesn't successfully get added to the queue, it does not break the SDK's state. So, we can ignore the result of adding task to queue.
        backgroundQueue.queueTrackMetric(deliveryID, deviceToken, event)
    }
}
