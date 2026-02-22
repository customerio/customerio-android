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

    /**
     * Event published when user identity changes (identify or clearIdentify).
     *
     * @param userId The user ID if identified, null if anonymous
     * @param anonymousId The anonymous ID (always present)
     */
    data class UserChangedEvent(
        val userId: String?,
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
     * Event emitted when a new location is available.
     * Published by the Location module on every location update.
     * DataPipelines applies the userId gate and sync filter (24h + 1km)
     * before sending to the server.
     */
    data class TrackLocationEvent(
        val location: LocationData
    ) : Event()

    /**
     * Location data in a framework-agnostic format.
     * Used to pass location information between modules without
     * requiring Android location framework imports.
     */
    data class LocationData(
        val latitude: Double,
        val longitude: Double
    )
}
