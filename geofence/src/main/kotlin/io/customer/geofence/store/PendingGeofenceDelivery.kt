package io.customer.geofence.store

import io.customer.sdk.communication.Event
import io.customer.sdk.data.store.PendingDeliveryStore
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable

/**
 * A geofence transition observed locally but not yet confirmed as tracked by
 * the Customer.io backend. Appended when a transition fires, removed when one
 * of the two delivery channels — the [GeofenceEventWorker] (durable, direct
 * HTTP) or the foreground flush (analytics pipeline) — delivers it.
 *
 * The shared [PendingDeliveryStore] requires a stable `key`; ours doubles as
 * the WorkManager unique-work name, so the foreground flush can cancel the
 * pending worker by the same key before publishing.
 */
@Serializable
internal data class PendingGeofenceDelivery(
    val geofenceId: String,
    val transition: Event.GeofenceTransition,
    /** Unix epoch **seconds** at receiver time. Use [toGeofenceTransitionEvent] when a [Date] is needed. */
    val timestamp: Long,
    val userId: String?,
    /** Null when the fired geofence isn't in the cached region set. */
    val geofenceName: String? = null,
    // --- Testing-only (geofence-testing branch): trigger context so the tester
    // can verify distance vs radius on the dashboard. Not present on feature/main.
    val triggerLatitude: Double? = null,
    val triggerLongitude: Double? = null,
    val distanceMeters: Double? = null,
    val geofenceRadius: Double? = null
) : PendingDeliveryStore.PendingDeliveryEntry {
    override val key: String get() = "${geofenceId}_${transition.name}_$timestamp"

    /**
     * Properties carried on the tracked "Geofence Transition" event. Kept here
     * so the worker's direct-HTTP send and the foreground flush build an
     * identical property set. Timestamp is not a property — each delivery path
     * sets it on the event envelope from [timestamp].
     */
    fun toEventProperties(): Map<String, Any> = buildMap {
        put("transition", transition.name.lowercase())
        put("geofenceId", geofenceId)
        geofenceName?.let { put("geofenceName", it) }
        // Testing-only (geofence-testing branch): trigger location, timestamp,
        // and distance-vs-radius so they're visible on the dashboard payload.
        put("timestamp", timestamp)
        triggerLatitude?.let { put("triggerLatitude", it) }
        triggerLongitude?.let { put("triggerLongitude", it) }
        distanceMeters?.let { put("distanceMeters", it) }
        geofenceRadius?.let { put("geofenceRadius", it) }
    }

    /**
     * Builds the EventBus event the foreground flush publishes for this row.
     * Owns the seconds→milliseconds conversion on [timestamp] so no caller has
     * to construct a [Date] from the raw [Long] (which would silently produce
     * a date in January 1970 if passed seconds).
     */
    fun toGeofenceTransitionEvent(): Event.GeofenceTransitionEvent =
        Event.GeofenceTransitionEvent(
            geofenceId = geofenceId,
            transition = transition,
            properties = toEventProperties(),
            userId = userId,
            timestamp = Date(TimeUnit.SECONDS.toMillis(timestamp))
        )

    companion object {
        internal const val FILE_NAME = "cio_pending_geofence_delivery.json"
    }
}
