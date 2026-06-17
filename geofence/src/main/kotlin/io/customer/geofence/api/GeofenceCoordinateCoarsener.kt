package io.customer.geofence.api

import java.math.RoundingMode

/**
 * Snaps the coordinates sent to `/geofences/nearby` to a ~1km grid (rounding to [GRID_DECIMALS]
 * decimal places; 0.01° ≈ 1.1km latitude) so the SDK never transmits the device's exact position.
 * Precise location stays on-device for proximity and movement-trigger logic.
 *
 * Snapping is deterministic, not jittered: repeated syncs from the same area send the same value, so
 * averaging many requests can't recover the true position. The server fetch radius must cover the
 * re-sync displacement plus this cell, or a geofence near a cell boundary can be missed.
 */
internal object GeofenceCoordinateCoarsener {
    private const val GRID_DECIMALS = 2

    fun coarsen(coordinate: Double): Double =
        coordinate.toBigDecimal().setScale(GRID_DECIMALS, RoundingMode.HALF_EVEN).toDouble()
}
