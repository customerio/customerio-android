package io.customer.location.geofence

/**
 * Selects the geofence regions closest to a reference location, capped at a maximum count.
 *
 * The OS limits the number of geofences an app can register simultaneously; this filter
 * picks the most relevant subset based on straight-line distance from the device's
 * current location.
 */
internal class GeofenceDistanceFilter {
    fun nearest(
        regions: List<GeofenceRegion>,
        latitude: Double,
        longitude: Double,
        max: Int
    ): List<GeofenceRegion> {
        if (max <= 0 || regions.isEmpty()) return emptyList()
        return regions
            .sortedBy { it.distanceTo(latitude, longitude) }
            .take(max)
    }
}
