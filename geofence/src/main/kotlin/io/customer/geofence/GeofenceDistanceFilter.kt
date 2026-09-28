package io.customer.geofence

import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.polygon.PolygonCoordinate
import io.customer.geofence.polygon.PolygonGeometry
import io.customer.geofence.polygon.PolygonSupport
import io.customer.sdk.core.di.SDKComponent
import kotlin.math.round

/**
 * Ties break on id after rounding to whole meters: `distanceBetween` varies sub-meter for the same
 * inputs, and iOS does the same so both pick the same set at the cap. Also the last gate before
 * registration, so unmonitorable polygons are dropped here.
 */
internal class GeofenceDistanceFilter(
    private val polygonSupport: PolygonSupport = PolygonSupport.Disabled,
    /**
     * Play services rejects a whole batch past [GeofenceConstants.MAX_OS_GEOFENCES], and the movement
     * trigger takes one slot. Server config can only lower it.
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
     * @param max server cap, `0` is the kill switch. Pins count toward it, but neither it nor
     * `maxDistanceMeters` evicts them. [maxOsBusinessSlots] still does: over-pinning fails the batch.
     * @param pinnedIds regions with a business EXIT outstanding; evicting one loses that EXIT.
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

    /** `null` means the region must not be registered. */
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

    /** Validation is O(V²) and ranking re-runs on every movement trigger, so `null` is cached too. */
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
