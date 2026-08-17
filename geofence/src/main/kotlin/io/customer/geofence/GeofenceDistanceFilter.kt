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
    /**
     * Hard ceiling on how many regions any [nearest] call may return.
     *
     * Play services rejects an entire `addGeofences` batch once the app would pass
     * [GeofenceConstants.MAX_OS_GEOFENCES], and one of those slots is always spent on the movement
     * trigger that [GeofenceRepository] prepends — so this filter, which only ever ranks *business*
     * regions, may never hand back more than [GeofenceConstants.MAX_OS_BUSINESS_GEOFENCE_SLOTS].
     * Server config can lower the count further via `max`; nothing can raise it past this.
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
     * @param max server-configured discovery cap. Bounds how many *new* regions this pass may pick
     * up; `0` is the explicit kill switch. Pinned regions are exempt (see below).
     * @param pinnedIds regions that must survive discovery caps because a business EXIT is still
     * outstanding for them — a polygon with an active fine session or a committed INSIDE state.
     * Evicting one guarantees its EXIT is never observed, so pinning outranks [max].
     *
     * Pinning cannot outrank the platform, though: [maxOsBusinessSlots] still bounds the result,
     * because over-pinning would make Play services reject the whole batch and lose *every* fence
     * rather than the farthest one. When more regions are pinned than there are slots, the nearest
     * pinned regions are kept — the farthest are least likely to produce an imminent EXIT — and each
     * released region is logged.
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
        // Same body for both overloads, so a caller that passes no pins is bounded identically.
        pruneGeometryCache(regions)
        val sorted = regions
            .mapNotNull { region ->
                rankingDistanceOrNull(region, latitude, longitude)?.let { distance -> region to distance }
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
        val retainedPinned = pinned.take(availableSlots)
        pinned.drop(availableSlots).forEach { (region, _) ->
            logger.logPinnedRegionDroppedAtOsLimit(region.id, availableSlots)
        }
        // Discovery gets whatever the *lower* of the two ceilings leaves over: the server cap it was
        // configured with, and the OS slots the pinned set didn't already consume.
        val discoveryBudget = minOf(
            (max - retainedPinned.size).coerceAtLeast(0),
            availableSlots - retainedPinned.size
        )
        return (retainedPinned + candidates.take(discoveryBudget))
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
