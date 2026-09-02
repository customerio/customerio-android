package io.customer.geofence

/**
 * When a fix was taken, carried alongside the coordinates it describes. Null when the source did
 * not report it.
 */
internal data class GeofenceFixQuality(
    val fixElapsedRealtimeMillis: Long? = null
) {
    /**
     * Whether the fix still describes where the device is. Both sides monotonic since boot, so a
     * stamp ahead of now can only be skew in a supplied value and is treated as current.
     */
    fun isFresh(nowElapsedRealtimeMillis: Long): Boolean {
        val takenAt = fixElapsedRealtimeMillis ?: return true
        return nowElapsedRealtimeMillis - takenAt <= GeofenceConstants.MAX_LIVE_FIX_AGE_MS
    }

    internal companion object {
        val UNKNOWN = GeofenceFixQuality()
    }
}
