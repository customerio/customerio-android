package io.customer.geofence

/**
 * What a fix can be trusted to resolve, carried alongside the coordinates it describes. Either
 * field is null when the source did not report it.
 */
internal data class GeofenceFixQuality(
    val accuracyMeters: Double? = null,
    val fixTimeMillis: Long? = null
) {
    /**
     * How far the device may be from where this fix puts it. Unknown accuracy means no margin: a
     * host supplying its own location is asserting a position, not measuring one.
     */
    val containmentMarginMeters: Double
        get() = accuracyMeters?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0

    /** Whether the fix is recent enough to still describe where the device is. */
    fun isFresh(nowMillis: Long): Boolean {
        val takenAt = fixTimeMillis ?: return true
        // A future stamp is a clock disagreement, not evidence of freshness.
        return (nowMillis - takenAt) in 0..GeofenceConstants.MAX_LIVE_FIX_AGE_MS
    }

    internal companion object {
        val UNKNOWN = GeofenceFixQuality()
    }
}
