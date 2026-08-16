package io.customer.geofence

import io.customer.geofence.polygon.PolygonCoordinate
import io.customer.geofence.polygon.PolygonGeometry
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
    private val geometryCache = mutableMapOf<String, CachedPolygonGeometry>()

    fun nearest(
        regions: List<GeofenceRegion>,
        latitude: Double,
        longitude: Double,
        max: Int,
        maxDistanceMeters: Float
    ): List<GeofenceRegion> = nearest(
        regions = regions,
        latitude = latitude,
        longitude = longitude,
        max = max,
        maxDistanceMeters = maxDistanceMeters,
        pinnedIds = emptySet()
    )

    fun nearest(
        regions: List<GeofenceRegion>,
        latitude: Double,
        longitude: Double,
        max: Int,
        maxDistanceMeters: Float,
        pinnedIds: Set<String>
    ): List<GeofenceRegion> {
        if (max <= 0 || regions.isEmpty()) return emptyList()
        pruneGeometryCache(regions)
        val sorted = regions
            .map { region ->
                region to round(
                    region.edgeDistanceTo(latitude, longitude, cachedGeometry(region))
                )
            }
            .filter { (region, distance) -> region.id in pinnedIds || distance <= maxDistanceMeters }
            .sortedWith(
                compareByDescending<Pair<GeofenceRegion, Float>> { (region, _) -> region.id in pinnedIds }
                    .thenBy { (_, distance) -> distance }
                    .thenBy { (region, _) -> region.id }
            )
        val (pinned, candidates) = sorted.partition { (region, _) -> region.id in pinnedIds }
        // A positive server cap controls discovery, but it must not evict a polygon whose fine
        // session or committed INSIDE state is already active. Such an eviction can never observe
        // the matching EXIT. max=0 remains the explicit kill switch above.
        return (pinned + candidates.take((max - pinned.size).coerceAtLeast(0)))
            .map { (region, _) -> region }
    }

    @Synchronized
    private fun cachedGeometry(region: GeofenceRegion): PolygonGeometry? {
        val vertices = region.polygonVertices ?: return null
        val cached = geometryCache[region.id]
        if (cached?.vertices == vertices) return cached.geometry
        return PolygonGeometry.from(vertices).also { geometry ->
            geometryCache[region.id] = CachedPolygonGeometry(vertices, geometry)
        }
    }

    @Synchronized
    private fun pruneGeometryCache(regions: List<GeofenceRegion>) {
        val polygonIds = regions.asSequence()
            .filter(GeofenceRegion::isPolygon)
            .mapTo(mutableSetOf(), GeofenceRegion::id)
        geometryCache.keys.retainAll(polygonIds)
    }

    private data class CachedPolygonGeometry(
        val vertices: List<PolygonCoordinate>,
        val geometry: PolygonGeometry
    )
}
