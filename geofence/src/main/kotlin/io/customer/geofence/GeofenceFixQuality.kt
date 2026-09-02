package io.customer.geofence

/**
 * When a fix was taken, carried alongside the coordinates it describes. Null when the source did
 * not report it.
 */
internal data class GeofenceFixQuality(
    val fixElapsedRealtimeMillis: Long? = null
) {
    /**
     * Whether the fix still describes where the device is. Both sides monotonic since boot, so our
     * own fixes can never be stamped ahead of now; one that is came from a host-supplied time that
     * cannot be trusted to judge geometry, and it ranks and prunes like an anchor instead.
     */
    fun isFresh(nowElapsedRealtimeMillis: Long): Boolean {
        val takenAt = fixElapsedRealtimeMillis ?: return true
        return (nowElapsedRealtimeMillis - takenAt) in 0..GeofenceConstants.MAX_LIVE_FIX_AGE_MS
    }

    internal companion object {
        val UNKNOWN = GeofenceFixQuality()
    }
}
