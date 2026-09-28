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
 * A geofence transition observed locally but not yet confirmed as tracked by the Customer.io
 * backend. Appended when a transition fires, removed when a delivery channel delivers it: the
 * WorkManager worker or async fallback (direct HTTP), or the foreground flush (analytics pipeline).
 */
@Serializable
internal data class PendingGeofenceDelivery(
    val geofenceId: String,
    val transition: Event.GeofenceTransition,
    /** Unix epoch **seconds** of the crossing. Use [toGeofenceTransitionEvent] when a [Date] is needed. */
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

    /**
     * Properties of the tracked "Geofence Transition" event, shared so every delivery path sends the
     * same set. Timestamp is not a property; each path sets it on the envelope from [timestamp].
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
     * The EventBus event the foreground flush publishes for this row. Owns the seconds-to-millis
     * conversion of [timestamp]; a [Date] built from raw seconds would land in January 1970.
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
 * Prefers the fence's current cached name and metadata, falling back to the crossing-time snapshot
 * when it has left the cache. Both fields come from one source so they never mix points in time.
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
