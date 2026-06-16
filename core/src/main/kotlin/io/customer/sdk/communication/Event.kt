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

    /**
     * Published by the location module on every fresh location fix. Other modules
     * subscribe to react to location updates without depending on its internals.
     */
    data class LocationAcquired(
        val latitude: Double,
        val longitude: Double
    ) : Event()

    class DeleteDeviceTokenEvent : Event()

    enum class GeofenceTransition {
        ENTER,
        EXIT
    }

    /**
     * Event published by the Location module when a geofence transition is received from the OS.
     * Subscribers (e.g. data pipelines) translate this into a tracked event.
     *
     * [userId] is snapshotted at queue time, not the SDK's current identity — non-null means the
     * subscriber must attribute the resulting track event to this userId (not whoever is identified
     * at flush time), so a sign-out + sign-in between queue and delivery cannot reattribute. Null
     * means anonymous at queue time; the subscriber falls back to the pipeline's anonymousId path.
     *
     * [timestamp] is the moment the transition fired (captured at receiver time), not the publish
     * time. Subscribers stamp this onto the outgoing CDP event so a delayed flush still attributes
     * the transition to when it happened, not when it was sent.
     */
    data class GeofenceTransitionEvent(
        val geofenceId: String,
        val transition: GeofenceTransition,
        val properties: Map<String, Any>,
        val userId: String?,
        override val timestamp: Date
    ) : Event()
}
