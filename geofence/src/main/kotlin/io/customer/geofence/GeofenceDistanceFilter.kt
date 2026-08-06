package io.customer.geofence

import kotlin.math.round

/**
 * Selects the geofence regions closest to a reference location, capped at a maximum count and
 * (optionally) a maximum distance.
 *
 * The OS limits the number of geofences an app can register simultaneously; this filter
 * picks the most relevant subset based on straight-line distance from the device's
 * current location. Regions whose boundary is beyond [maxDistanceMeters] are excluded entirely;
 * local re-ranking re-includes them as the device approaches.
 *
 * Ranking and the cap both measure to the region's *boundary* ([edgeDistanceTo]), so a region the
 * device is inside always sorts first and survives both the count limit and the distance cap.
 *
 * Ties break on ascending [GeofenceRegion.id], and distances round to whole meters first:
 * `Location.distanceBetween` can return sub-meter-varying results for the same inputs, which would
 * otherwise defeat the tiebreak and leave equidistant regions ordered by the server's response
 * order. Matches iOS so both platforms pick the same set at the cap.
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
            .map { it to round(it.edgeDistanceTo(latitude, longitude)) }
            .filter { (_, distance) -> distance <= maxDistanceMeters }
            .sortedWith(compareBy({ (_, distance) -> distance }, { (region, _) -> region.id }))
            .take(max)
            .map { (region, _) -> region }
    }
}
