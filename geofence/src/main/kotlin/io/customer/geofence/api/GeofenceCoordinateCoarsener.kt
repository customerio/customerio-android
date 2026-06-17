package io.customer.geofence.api

import java.math.RoundingMode

/**
 * Snaps coordinates sent to `/geofences/nearby` to a ~1km grid (0.01° ≈ 1.1km) so the SDK never
 * transmits the device's exact position — precise location stays on-device for proximity logic.
 *
 * Deterministic, not jittered: the same area always sends the same value, so averaging repeated
 * requests can't recover the true point.
 */
internal object GeofenceCoordinateCoarsener {
    private const val GRID_DECIMALS = 2

    fun coarsen(coordinate: Double): Double =
        coordinate.toBigDecimal().setScale(GRID_DECIMALS, RoundingMode.HALF_EVEN).toDouble()
}
