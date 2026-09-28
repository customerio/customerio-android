package io.customer.geofence

import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.polygon.PolygonCoordinate
import io.customer.geofence.polygon.PolygonGeometry
import io.customer.geofence.polygon.PolygonSupport
import io.customer.sdk.core.di.SDKComponent
import kotlin.math.round

/**
 * Selects the geofence regions closest to a reference location, capped at a maximum count and a
 * maximum distance, because the OS limits how many geofences an app can register. Regions beyond
 * `maxDistanceMeters` are excluded; local re-ranking re-includes them as the device approaches.
 *
 * Ranking and the cap measure to the region's *boundary* ([edgeDistanceToOrNull]), so a region the
 * device is inside sorts first and survives both limits.
 *
 * Ties break on ascending [GeofenceRegion.id], after rounding distances to whole meters:
 * `Location.distanceBetween` can vary sub-meter for the same inputs, which would defeat the
 * tiebreak. Matches iOS so both platforms pick the same set at the cap.
 *
 * As the last gate before registration, this is also where an unmonitorable polygon is dropped
 * (see [polygonSupport]).
 */
internal class GeofenceDistanceFilter(
    private val polygonSupport: PolygonSupport = PolygonSupport.Disabled,
    /**
     * Hard ceiling on how many regions any [nearest] call may return. Play services rejects an
     * entire `addGeofences` batch past [GeofenceConstants.MAX_OS_GEOFENCES], and one slot always
     * goes to the movement trigger [GeofenceRepository] prepends. Server config can only lower it.
     */
    private val maxOsBusinessSlots: Int = GeofenceConstants.MAX_OS_BUSINESS_GEOFENCE_SLOTS,
    private val logger: GeofenceLogger = SDKComponent.geofenceLogger
) {
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

    /**
     * @param max server-configured discovery cap; `0` is the explicit kill switch. Pinned regions
     * count toward it but are never evicted by it or by `maxDistanceMeters`.
     * @param pinnedIds regions with a business EXIT still outstanding (decided by the caller, which
     * holds registration state). Evicting one means its EXIT is never observed.
     *
     * [maxOsBusinessSlots] still bounds pins: over-pinning would make Play services reject the whole
     * batch. When pins exceed the slots, the nearest are kept and each dropped one is logged.
     */
    fun nearest(
        regions: List<GeofenceRegion>,
        latitude: Double,
        longitude: Double,
        max: Int,
        maxDistanceMeters: Float,
        pinnedIds: Set<String>
    ): List<GeofenceRegion> {
        val availableSlots = maxOsBusinessSlots.coerceAtLeast(0)
        if (max <= 0 || availableSlots == 0 || regions.isEmpty()) return emptyList()
        pruneGeometryCache(regions)
        val allPinnedIds = pinnedIds
        val sorted = regions
            .mapNotNull { region ->
                rankingDistanceOrNull(region, latitude, longitude)?.let { distance -> region to distance }
            }
            .filter { (region, distance) -> region.id in allPinnedIds || distance <= maxDistanceMeters }
            .sortedWith(
                compareByDescending<Pair<GeofenceRegion, Float>> { (region, _) -> region.id in allPinnedIds }
                    .thenBy { (_, distance) -> distance }
                    .thenBy { (region, _) -> region.id }
            )
        val (pinned, candidates) = sorted.partition { (region, _) -> region.id in allPinnedIds }
        val retainedPinned = pinned.take(availableSlots)
        pinned.drop(availableSlots).forEach { (region, _) ->
            logger.logPinnedRegionDroppedAtOsLimit(region.id, availableSlots)
        }
        val discoveryBudget = minOf(
            (max - retainedPinned.size).coerceAtLeast(0),
            availableSlots - retainedPinned.size
        )
        return (retainedPinned + candidates.take(discoveryBudget))
            .map { (region, _) -> region }
    }

    /**
     * Distance this region ranks by, or `null` when it must not be registered: a polygon when this
     * build can't monitor polygons or its ring doesn't validate. Never falls back to the circle
     * fields, which describe the coarse enclosing trigger rather than the fence.
     */
    private fun rankingDistanceOrNull(
        region: GeofenceRegion,
        latitude: Double,
        longitude: Double
    ): Float? {
        if (region.isPolygon && !polygonSupport.isPolygonMonitoringEnabled) {
            logger.logPolygonRegionNotRanked(region.id, PolygonNotRankedReason.RUNTIME_UNSUPPORTED)
            return null
        }
        val distance = region.edgeDistanceToOrNull(latitude, longitude, cachedGeometry(region))
        if (distance == null) {
            logger.logPolygonRegionNotRanked(region.id, PolygonNotRankedReason.RING_UNBUILDABLE)
            return null
        }
        return round(distance)
    }

    /**
     * Validated geometry for [region], memoized per id + ring. Validation is O(V²) and ranking
     * re-runs on every movement trigger over an unchanged catalog, so the `null` outcome is cached
     * too.
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
