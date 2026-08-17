package io.customer.geofence

import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.polygon.PolygonCoordinate
import io.customer.geofence.polygon.PolygonGeometry
import io.customer.geofence.polygon.PolygonSupport
import io.customer.sdk.core.di.SDKComponent
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
 * Ranking and the cap both measure to the region's *boundary* ([edgeDistanceToOrNull]), so a region
 * the device is inside always sorts first and survives both the count limit and the distance cap.
 *
 * Ties break on ascending [GeofenceRegion.id], and distances round to whole meters first:
 * `Location.distanceBetween` can return sub-meter-varying results for the same inputs, which would
 * otherwise defeat the tiebreak and leave equidistant regions ordered by the server's response
 * order. Matches iOS so both platforms pick the same set at the cap.
 *
 * This is the last gate before registration, so it is also where a polygon that has no business
 * being monitored is dropped — see [polygonSupport].
 */
internal class GeofenceDistanceFilter(
    private val polygonSupport: PolygonSupport = PolygonSupport.Disabled,
    private val logger: GeofenceLogger = SDKComponent.geofenceLogger
) {
    private val geometryCache = mutableMapOf<String, CachedPolygonGeometry>()

    fun nearest(
        regions: List<GeofenceRegion>,
        latitude: Double,
        longitude: Double,
        max: Int,
        maxDistanceMeters: Float
    ): List<GeofenceRegion> {
        if (max <= 0 || regions.isEmpty()) return emptyList()
        pruneGeometryCache(regions)
        return regions
            .mapNotNull { region ->
                rankingDistanceOrNull(region, latitude, longitude)?.let { distance -> region to distance }
            }
            .filter { (_, distance) -> distance <= maxDistanceMeters }
            .sortedWith(compareBy({ (_, distance) -> distance }, { (region, _) -> region.id }))
            .take(max)
            .map { (region, _) -> region }
    }

    /**
     * Distance this region ranks by, or `null` when it must not be registered at all.
     *
     * A polygon is unrankable — and so unregisterable — when this build can't monitor polygons, or
     * when its cached ring no longer validates. Both drop only that region: the rest of the cached
     * catalog still ranks and re-registers, and neither case falls back to the region's circle
     * fields, which describe the coarse enclosing trigger rather than the fence itself.
     */
    private fun rankingDistanceOrNull(
        region: GeofenceRegion,
        latitude: Double,
        longitude: Double
    ): Float? {
        if (region.isPolygon && !polygonSupport.isPolygonMonitoringEnabled) {
            logger.logPolygonRegionNotRanked(region.id, "polygon monitoring is not enabled in this build")
            return null
        }
        val distance = region.edgeDistanceToOrNull(latitude, longitude, cachedGeometry(region))
        if (distance == null) {
            logger.logPolygonRegionNotRanked(region.id, "the cached ring no longer validates")
            return null
        }
        return round(distance)
    }

    /**
     * Validated geometry for [region], memoized per id + ring.
     *
     * Validation is O(V²) (self-intersection), and ranking re-runs on every movement trigger over an
     * unchanged catalog, so the outcome is cached — including the `null` outcome, or a ring that can
     * never validate would pay the full check on every pass. Wire validation is untouched: this
     * caches the result of the same [PolygonGeometry.fromOrNull] call, it doesn't skip it.
     */
    @Synchronized
    private fun cachedGeometry(region: GeofenceRegion): PolygonGeometry? {
        val vertices = region.polygonVertices ?: return null
        val cached = geometryCache[region.id]
        if (cached != null && cached.vertices == vertices) return cached.geometry
        return PolygonGeometry.fromOrNull(vertices).also { geometry ->
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
        val geometry: PolygonGeometry?
    )
}
