package io.customer.geofence

/**
 * What a fix can be trusted to resolve, carried alongside the coordinates it describes. Either
 * field is null when the source did not report it.
 */
internal data class GeofenceFixQuality(
    val accuracyMeters: Double? = null,
    val fixElapsedRealtimeMillis: Long? = null
) {
    /**
     * How far the device may be from where this fix puts it. Unknown accuracy means no margin: a
     * host supplying its own location is asserting a position, not measuring one.
     */
    val containmentMarginMeters: Double
        get() = accuracyMeters?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0

    /** Whether the fix still describes where the device is. Both sides monotonic since boot. */
    fun isFresh(nowElapsedRealtimeMillis: Long): Boolean {
        val takenAt = fixElapsedRealtimeMillis ?: return true
        return (nowElapsedRealtimeMillis - takenAt) in 0..GeofenceConstants.MAX_LIVE_FIX_AGE_MS
    }

    internal companion object {
        val UNKNOWN = GeofenceFixQuality()
    }
}
