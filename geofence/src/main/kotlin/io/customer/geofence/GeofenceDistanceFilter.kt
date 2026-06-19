package io.customer.geofence

/**
 * Selects the geofence regions closest to a reference location, capped at a maximum count and
 * (optionally) a maximum distance.
 *
 * The OS limits the number of geofences an app can register simultaneously; this filter
 * picks the most relevant subset based on straight-line distance from the device's
 * current location. Regions beyond [maxDistanceMeters] are excluded entirely; local re-ranking
 * re-includes them as the device approaches.
 */
internal class GeofenceDistanceFilter {
    fun nearest(
        regions: List<GeofenceRegion>,
        latitude: Double,
        longitude: Double,
        max: Int,
        maxDistanceMeters: Float
    ): List<GeofenceRegion> {
        if (max <= 0 || regions.isEmpty()) return emptyList()
        return regions
            .filter { it.distanceTo(latitude, longitude) <= maxDistanceMeters }
            .sortedBy { it.distanceTo(latitude, longitude) }
            .take(max)
    }
}
