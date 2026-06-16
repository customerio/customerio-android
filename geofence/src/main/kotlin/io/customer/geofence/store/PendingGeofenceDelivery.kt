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
    val userId: String?
) : PendingDeliveryStore.PendingDeliveryEntry {
    override val key: String get() = "${geofenceId}_${transition.name}_$timestamp"

    /**
     * Properties carried on the tracked "Geofence Entered/Exited" event. Kept
     * here so the producer, the worker's direct-HTTP send, and the foreground
     * flush all build an identical property set.
     */
    fun toEventProperties(): Map<String, Any> = buildMap {
        put("geofence_id", geofenceId)
        put("transition_type", transition.name.lowercase())
        put("timestamp", timestamp)
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
