package io.customer.geofence

/**
 * When a fix was taken, carried alongside the coordinates it describes. Null when the source did
 * not report it.
 */
internal data class GeofenceFixQuality(
    val fixElapsedRealtimeMillis: Long? = null
) {
    /**
     * Whether the fix still describes where the device is. Both clocks are monotonic since boot, so
     * a fix stamped ahead of now came from an untrusted host-supplied time.
     *
     * A stale fix drives no pass: [GeofenceServices] declines it and keeps the live-fix intent armed
     * so a later fix can still seed containment.
     */
    fun isFresh(nowElapsedRealtimeMillis: Long): Boolean {
        val takenAt = fixElapsedRealtimeMillis ?: return true
        return (nowElapsedRealtimeMillis - takenAt) in 0..GeofenceConstants.MAX_LIVE_FIX_AGE_MS
    }

    internal companion object {
        val UNKNOWN = GeofenceFixQuality()
    }
}
