package io.customer.sdk.communication

import io.customer.sdk.events.Metric
import java.util.Date
import java.util.UUID

/**
 * Class to represent an event that can be published and subscribed to using [EventBus].
 */
sealed class Event {
    // Unique identifier for the event
    open val storageId: String = UUID.randomUUID().toString()

    // Additional metadata associated with the event
    open val params: Map<String, String> = emptyMap()

    // Timestamp of the event
    open val timestamp: Date = Date()

    data class ProfileIdentifiedEvent(
        val identifier: String
    ) : Event()

    data class AnonymousIdGeneratedEvent(
        val anonymousId: String
    ) : Event()

    data class ScreenViewedEvent(
        val name: String
    ) : Event()

    object ResetEvent : Event()

    data class TrackPushMetricEvent(
        val deliveryId: String,
        val event: Metric,
        val deviceToken: String
    ) : Event()

    data class TrackInAppMetricEvent(
        val deliveryID: String,
        val event: Metric,
        override val params: Map<String, String> = emptyMap()
    ) : Event()

    data class RegisterDeviceTokenEvent(
        val token: String
    ) : Event()

    class DeleteDeviceTokenEvent : Event()

    /**
     * Event published when Analytics/SDK performs a flush operation.
     * Diagnostics can subscribe to this event to flush diagnostic data alongside analytics data.
     */
    object FlushEvent : Event()
}
