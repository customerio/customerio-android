package io.customer.geofence

internal data class GeofenceFixQuality(
    val fixElapsedRealtimeMillis: Long? = null
) {
    /**
     * Both clocks are monotonic since boot, so a fix stamped ahead of now has an untrusted host
     * time.
     */
    fun isFresh(nowElapsedRealtimeMillis: Long): Boolean {
        val takenAt = fixElapsedRealtimeMillis ?: return true
        return (nowElapsedRealtimeMillis - takenAt) in 0..GeofenceConstants.MAX_LIVE_FIX_AGE_MS
    }

    internal companion object {
        val UNKNOWN = GeofenceFixQuality()
    }
}
