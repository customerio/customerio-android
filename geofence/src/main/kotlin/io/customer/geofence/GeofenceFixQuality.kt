package io.customer.geofence

internal data class GeofenceFixQuality(
    val fixElapsedRealtimeMillis: Long? = null,
    /** Radius of 68% confidence, as [android.location.Location.getAccuracy] reports it. */
    val horizontalAccuracyMeters: Float? = null
) {
    /**
     * Both clocks are monotonic since boot, so a fix stamped ahead of now has an untrusted host
     * time.
     */
    fun isFresh(nowElapsedRealtimeMillis: Long): Boolean {
        val takenAt = fixElapsedRealtimeMillis ?: return true
        return (nowElapsedRealtimeMillis - takenAt) in 0..GeofenceConstants.MAX_LIVE_FIX_AGE_MS
    }

    /**
     * Whether this fix proves the device was outside a fence whose edge it lies [metersBeyondEdge]
     * beyond, recently enough that an ENTER after it is the crossing itself. Both the fix's time and
     * its accuracy must be known: a coarse or unstamped fix just past the edge may have come from a
     * device already inside, whose registration then reports ENTER without any crossing.
     */
    fun provesOutside(metersBeyondEdge: Float, nowElapsedRealtimeMillis: Long): Boolean {
        val takenAt = fixElapsedRealtimeMillis ?: return false
        if ((nowElapsedRealtimeMillis - takenAt) !in 0..GeofenceConstants.MAX_OUTSIDE_PROOF_AGE_MS) return false
        return clearsEdge(metersBeyondEdge)
    }

    /**
     * Whether the fix lies beyond a fence's edge by more than its accuracy plus the margin, with no
     * bound on age. Enough on its own only for a fence the OS was already monitoring when the fix
     * was taken: the proof then dates from the fix, and GMS covers the time since.
     */
    fun clearsEdge(metersBeyondEdge: Float): Boolean {
        val accuracy = horizontalAccuracyMeters?.takeIf { it.isFinite() && it >= 0f } ?: return false
        return metersBeyondEdge > accuracy + GeofenceConstants.OUTSIDE_PROOF_MARGIN_METERS
    }

    internal companion object {
        val UNKNOWN = GeofenceFixQuality()
    }
}
