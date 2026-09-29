package io.customer.geofence

import android.location.Location

/**
 * A region crossing as the OS reported it, in SDK terms. One GMS broadcast can name several
 * fences sharing one transition and one fix.
 */
internal data class GeofenceCrossing(
    val geofenceIds: List<String>,
    val transition: GeofenceCrossingTransition,
    /** The OS's own name for the transition, for the log only; [transition] is the interpreted form. */
    val transitionName: String,
    /** The OS's transition code, for the unsupported-transition record. */
    val rawTransitionCode: Int,
    /** The fix the OS attached to this crossing. Null when the OS supplied none. */
    val latitude: Double?,
    val longitude: Double?,
    /**
     * The OS fix object itself, which the polygon resolver needs beyond the coordinates — it reads
     * accuracy and the fix's own elapsed-realtime stamp to decide whether a verdict can be trusted.
     * Null when the OS supplied none, exactly as [latitude]/[longitude] are.
     */
    val triggeringLocation: Location?,
    /**
     * When the SDK received the broadcast, in unix seconds — the value that ships on the delivered
     * event. Stamped where the crossing is built, before any dispatch work, because everything
     * after that point is the SDK's own latency: resolving the pipeline on a cold process builds
     * the whole geofence graph, and a stamp taken past it dates the crossing to when we got
     * around to it rather than when the OS said it happened.
     */
    val receivedAtSeconds: Long
)

/** GMS also reports DWELL, which is never registered for and must not read as an arrival. */
internal enum class GeofenceCrossingTransition {
    ENTER,
    EXIT,
    UNSUPPORTED
}
