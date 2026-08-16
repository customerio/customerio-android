package io.customer.geofence.store

import io.customer.geofence.GeofenceRegion
import io.customer.sdk.communication.Event
import io.customer.sdk.data.store.PendingDeliveryStore
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

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
    /**
     * Identifies the physical crossing, shared across its per-geoset fan-out (geosets differ by
     * [geosetId]); backend dedup is keyed (transitionId, geoset).
     */
    val transitionId: String,
    /** Null when the fired geofence isn't in the cached region set. */
    val geofenceName: String? = null,
    /** Part of [key] so per-geoset entries for one crossing don't collide; null when the fence has no geosets. */
    val geosetId: String? = null,
    /** Snapshot of the fence's `metadata` at crossing time; the send-time fallback when it's left the cache. */
    val metadata: Map<String, JsonElement> = emptyMap(),
    /** User-state generation that observed this crossing. Internal only, never sent as event data. */
    val stateGeneration: Long = 0L,
    /** Geometry revision used to reject a transition computed from a replaced polygon. */
    val regionRevision: Int? = null,
    /** Whether committing this staged ENTER must atomically set the reboot dedupe marker. */
    val marksEnterReported: Boolean = false
) : PendingDeliveryStore.PendingDeliveryEntry {
    override val key: String
        get() = "${geofenceId}_${transition.name}_${transitionId}_${geosetId ?: "none"}"

    /** Pre-transitionId key used only to find WorkManager jobs queued by an older SDK build. */
    val legacyKey: String
        get() = "${geofenceId}_${transition.name}_${timestamp}_${geosetId ?: "none"}"

    /**
     * Properties carried on the tracked "Geofence Transition" event. Kept here
     * so the worker's direct-HTTP send and the foreground flush build an
     * identical property set. Timestamp is not a property — each delivery path
     * sets it on the event envelope from [timestamp].
     */
    fun toEventProperties(): Map<String, Any> = buildMap {
        put("transition", transition.name.lowercase())
        put("geofenceId", geofenceId)
        put("transitionId", transitionId)
        geosetId?.let { put("geosetId", it) }
        geofenceName?.let { put("geofenceName", it) }
        // Always present (empty when the fence has none), unlike the optional fields above.
        put("metadata", metadata.toEventMetadata())
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

/**
 * Prefers the fence's current cached name + metadata, falling back to the crossing-time snapshot when
 * it has left the cache. Both fields move together so they never mix points in time. Applied by every
 * delivery path so all send an identical, consistently-sourced set.
 */
internal fun PendingGeofenceDelivery.withFreshestEventData(cachedRegion: GeofenceRegion?): PendingGeofenceDelivery {
    if (cachedRegion == null) {
        return this
    }

    return copy(
        geofenceName = cachedRegion.name?.takeIf { it.isNotEmpty() },
        metadata = cachedRegion.metadata
    )
}

// org.json's JSONObject and Segment's serializer reject JsonElement, so unwrap to Kotlin primitives;
// non-scalars are already gone by ingestion but drop defensively here too.
private fun Map<String, JsonElement>.toEventMetadata(): Map<String, Any> = buildMap {
    this@toEventMetadata.forEach { (key, element) ->
        (element as? JsonPrimitive)?.toKotlinPrimitiveOrNull()?.let { put(key, it) }
    }
}

private fun JsonPrimitive.toKotlinPrimitiveOrNull(): Any? = when {
    isString -> content
    booleanOrNull != null -> booleanOrNull
    longOrNull != null -> longOrNull
    doubleOrNull != null -> doubleOrNull
    else -> null
}
