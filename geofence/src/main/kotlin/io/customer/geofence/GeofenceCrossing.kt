package io.customer.geofence

/**
 * A region crossing as the OS reported it, in SDK terms.
 *
 * The neutral value that separates the two halves of the crossing path: `GeofenceBroadcastReceiver`
 * knows GMS and builds one of these; [GeofenceCrossingPipeline] decides what to do with it and
 * knows no GMS at all. Substituting at this line replaces the OS and nothing else — which is the
 * whole point, because every decision worth testing is on the far side of it.
 *
 * GMS batches: one broadcast can name several fences, all sharing one transition and one fix.
 */
internal data class GeofenceCrossing(
    val geofenceIds: List<String>,
    val transition: GeofenceCrossingTransition,
    /**
     * What the OS called it — `ENTER`, `DWELL`, `UNKNOWN(7)`. Carried for the log only, never
     * branched on; [transition] is the interpreted form.
     */
    val transitionName: String,
    /**
     * The OS's own transition code, so a record about discarding an unsupported transition can say
     * which one arrived. Opaque to the pipeline.
     */
    val rawTransitionCode: Int,
    /** The fix the OS attached to this crossing. Null when the OS supplied none. */
    val latitude: Double?,
    val longitude: Double?
)

/**
 * The crossings the SDK acts on, plus the catch-all for everything else.
 *
 * GMS also reports DWELL, which the SDK never registers for and must not treat as an arrival —
 * hence a third case rather than a nullable ENTER/EXIT that a caller could forget to check.
 */
internal enum class GeofenceCrossingTransition {
    ENTER,
    EXIT,
    UNSUPPORTED
}
