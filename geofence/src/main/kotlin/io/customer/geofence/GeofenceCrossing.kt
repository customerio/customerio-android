package io.customer.geofence

import android.location.Location

/** One GMS broadcast, which can name several fences sharing one transition and one fix. */
internal data class GeofenceCrossing(
    val geofenceIds: List<String>,
    val transition: GeofenceCrossingTransition,
    /** For the log only; [transition] is the interpreted form. */
    val transitionName: String,
    val rawTransitionCode: Int,
    val latitude: Double?,
    val longitude: Double?,
    /** The OS fix itself; polygon evaluation needs its accuracy and elapsed-realtime stamp. */
    val triggeringLocation: Location?,
    /**
     * Unix seconds when the broadcast arrived; ships on the delivered event. Stamped before dispatch
     * work, because building the geofence graph on a cold process would otherwise skew it.
     */
    val receivedAtSeconds: Long,
    /** elapsedRealtime read with [receivedAtSeconds], so a visit can be measured on the boot clock. */
    val receivedAtElapsedMs: Long
)

/** GMS reports DWELL only for circles configured with a loitering delay. */
internal enum class GeofenceCrossingTransition {
    ENTER,
    DWELL,
    EXIT,
    UNSUPPORTED
}
