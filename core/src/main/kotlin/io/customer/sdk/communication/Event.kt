package io.customer.sdk.communication

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

    data class ScreenViewedEvent(
        val name: String
    ) : Event()

    object ResetEvent : Event()

    data class TrackPushMetricEvent(
        val deliveryId: String,
        val event: String,
        val deviceToken: String
    ) : Event()

    data class TrackInAppMetricEvent(
        val deliveryID: String,
        val event: String,
        override val params: Map<String, String>
    ) : Event()

    data class RegisterDeviceTokenEvent(
        val token: String
    ) : Event()

    class DeleteDeviceTokenEvent : Event()
}
