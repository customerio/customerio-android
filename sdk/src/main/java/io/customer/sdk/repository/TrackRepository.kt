package io.customer.sdk.repository

import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.data.model.EventType
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.hooks.HooksManager
import io.customer.sdk.hooks.ModuleHook
import io.customer.sdk.repository.preference.SitePreferenceRepository
import io.customer.sdk.util.Logger
import io.customer.shared.tracking.constant.TrackingType
import io.customer.shared.tracking.queue.BackgroundQueue

interface TrackRepository {
    fun track(name: String, attributes: CustomAttributes)
    fun trackMetric(deliveryID: String, event: MetricEvent, deviceToken: String)
    fun trackInAppMetric(deliveryID: String, event: MetricEvent)
    fun screen(name: String, attributes: CustomAttributes)
}

internal class TrackRepositoryImpl(
    private val sitePreferenceRepository: SitePreferenceRepository,
    private val backgroundQueue: BackgroundQueue,
    private val logger: Logger,
    private val hooksManager: HooksManager
) : TrackRepository {

    override fun track(name: String, attributes: CustomAttributes) {
        return track(EventType.event, name, attributes)
    }

    override fun screen(name: String, attributes: CustomAttributes) {
        return track(EventType.screen, name, attributes)
    }

    private fun track(eventType: EventType, name: String, attributes: CustomAttributes) {
        val eventTypeDescription =
            if (eventType == EventType.screen) "track screen view event" else "track event"

        logger.info("$eventTypeDescription $name")
        logger.debug("$eventTypeDescription $name attributes: $attributes")

        val identifier = sitePreferenceRepository.getIdentifier()
        if (identifier == null) {
            // when we have anonymous profiles implemented in the SDK, we can decide to not
            // ignore events when a profile is not logged in yet.
            logger.info("ignoring $eventTypeDescription $name because no profile currently identified")
//            return
        }

        backgroundQueue.queueTrack(identifier, name, eventType.trackingType, attributes) { queueStatus ->
            if (queueStatus.isSuccess && eventType == EventType.screen) {
                hooksManager.onHookUpdate(
                    hook = ModuleHook.ScreenTrackedHook(name)
                )
            }
        }
    }

    private val EventType.trackingType
        get() = when (this) {
            EventType.event -> TrackingType.EVENT
            EventType.screen -> TrackingType.SCREEN
        }

    private val MetricEvent.kmm
        get() = when (this) {
            MetricEvent.delivered -> io.customer.shared.tracking.constant.MetricEvent.DELIVERED
            MetricEvent.opened -> io.customer.shared.tracking.constant.MetricEvent.OPENED
            MetricEvent.converted -> io.customer.shared.tracking.constant.MetricEvent.CONVERTED
            MetricEvent.clicked -> io.customer.shared.tracking.constant.MetricEvent.CLICKED
        }

    override fun trackMetric(
        deliveryID: String,
        event: MetricEvent,
        deviceToken: String
    ) {
        logger.info("push metric ${event.name}")
        logger.debug("delivery id $deliveryID device token $deviceToken")

        // if task doesn't successfully get added to the queue, it does not break the SDK's state. So, we can ignore the result of adding task to queue.
        backgroundQueue.queueTrackMetric(deliveryID, deviceToken, event.kmm)
    }

    override fun trackInAppMetric(
        deliveryID: String,
        event: MetricEvent
    ) {
        logger.info("in-app metric ${event.name}")
        logger.debug("delivery id $deliveryID")

        // if task doesn't successfully get added to the queue, it does not break the SDK's state. So, we can ignore the result of adding task to queue.
        backgroundQueue.queueTrackInAppMetric(deliveryID, event.kmm)
    }
}
