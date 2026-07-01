package io.customer.geofence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Minimal lat/lng holder used to persist anchor points for tiered refresh. */
@Serializable
internal data class GeofenceLocation(
    @SerialName("latitude") val latitude: Double,
    @SerialName("longitude") val longitude: Double
)

/** Straight-line distance in meters from this location to the given coordinates. */
internal fun GeofenceLocation.distanceTo(lat: Double, lng: Double): Float {
    val result = FloatArray(1)
    android.location.Location.distanceBetween(latitude, longitude, lat, lng, result)
    return result[0]
}

// Ground grid (meters) the device location is snapped to before it's sent to the nearby API
// (NEARBY sync only). Uniform in both axes — the longitude grid is scaled by cos(latitude) — so the
// ~500 m floor holds at any latitude, unlike decimal rounding which over-refines longitude near the
// poles. Coarse enough to pick the right nearby set without transmitting a precise position.
private const val COARSE_LOCATION_GRID_METERS = 500.0

// Approx. meters per degree of latitude (near-constant), to convert the grid to degrees.
private const val METERS_PER_DEGREE_LATITUDE = 111_320.0

/**
 * Snaps the coordinates to a [gridMeters] ground grid, reducing precision before the location leaves
 * the device. Longitude degrees shrink toward the poles, so the longitude grid is scaled by
 * cos(latitude) to keep the ground spacing uniform. Used only for the NEARBY fetch request; the
 * precise value is kept for on-device math.
 */
internal fun GeofenceLocation.coarsened(gridMeters: Double = COARSE_LOCATION_GRID_METERS): GeofenceLocation {
    val latGridDegrees = gridMeters / METERS_PER_DEGREE_LATITUDE
    // Guard against cos → 0 at the poles (grid would blow up to the whole globe).
    val cosLatitude = Math.cos(Math.toRadians(latitude)).coerceAtLeast(1e-6)
    val lngGridDegrees = gridMeters / (METERS_PER_DEGREE_LATITUDE * cosLatitude)
    return GeofenceLocation(
        latitude = snapToGrid(latitude, latGridDegrees),
        longitude = snapToGrid(longitude, lngGridDegrees)
    )
}

// Snap to the grid, then trim binary-float noise (e.g. 37.775000000000006) so the coordinate
// stringifies cleanly into the request. 6 dp ≈ 0.1 m — far below the grid, so precision is unaffected.
private fun snapToGrid(value: Double, gridDegrees: Double): Double {
    val snapped = Math.round(value / gridDegrees) * gridDegrees
    return Math.round(snapped * 1_000_000.0) / 1_000_000.0
}
