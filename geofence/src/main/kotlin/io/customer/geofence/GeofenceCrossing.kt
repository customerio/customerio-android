package io.customer.geofence

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
    val longitude: Double?
)

/** GMS also reports DWELL, which is never registered for and must not read as an arrival. */
internal enum class GeofenceCrossingTransition {
    ENTER,
    EXIT,
    UNSUPPORTED
}
