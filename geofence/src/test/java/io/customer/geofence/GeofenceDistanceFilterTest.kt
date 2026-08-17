package io.customer.geofence

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.polygon.EnabledPolygonSupport
import io.customer.geofence.polygon.PolygonCoordinate
import io.customer.geofence.polygon.PolygonGeometry
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterThan
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceDistanceFilterTest : RobolectricTest() {

    private val filter = GeofenceDistanceFilter()
    private val noDistanceCap = GeofenceConstants.NO_MONITORING_DISTANCE_CAP_METERS

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { })
    }

    @Test
    fun nearest_givenEmptyList_expectEmpty() {
        filter.nearest(emptyList(), latitude = 0.0, longitude = 0.0, max = 5, maxDistanceMeters = noDistanceCap).shouldBeEmpty()
    }

    @Test
    fun nearest_givenMaxZero_expectEmpty() {
        val regions = listOf(region("biz-1", 0.0, 0.0))
        filter.nearest(regions, latitude = 0.0, longitude = 0.0, max = 0, maxDistanceMeters = noDistanceCap).shouldBeEmpty()
    }

    @Test
    fun nearest_givenFewerRegionsThanMax_expectAllReturnedSortedByDistance() {
        val reference = 0.0 to 0.0
        val far = region("biz-far", 5.0, 0.0)
        val close = region("biz-close", 0.01, 0.0)
        val mid = region("biz-mid", 1.0, 0.0)

        val result = filter.nearest(listOf(far, close, mid), reference.first, reference.second, max = 5, maxDistanceMeters = noDistanceCap)

        result.map { it.id } shouldBeEqualTo listOf("biz-close", "biz-mid", "biz-far")
    }

    @Test
    fun nearest_givenMoreRegionsThanMax_expectNearestMaxReturned() {
        val reference = 0.0 to 0.0
        val regions = listOf(
            region("biz-far", 5.0, 0.0),
            region("biz-close", 0.01, 0.0),
            region("biz-mid", 1.0, 0.0),
            region("biz-farther", 10.0, 0.0)
        )

        val result = filter.nearest(regions, reference.first, reference.second, max = 2, maxDistanceMeters = noDistanceCap)

        result.map { it.id } shouldBeEqualTo listOf("biz-close", "biz-mid")
    }

    @Test
    fun nearest_givenMaxDistance_expectRegionsBeyondCapExcluded() {
        val close = region("biz-close", 0.01, 0.0) // ~1.1 km
        val mid = region("biz-mid", 1.0, 0.0) // ~111 km
        val far = region("biz-far", 5.0, 0.0) // ~555 km

        // 50 km cap: only the ~1.1 km region qualifies; count budget is irrelevant here.
        val result = filter.nearest(
            listOf(far, close, mid),
            latitude = 0.0,
            longitude = 0.0,
            max = 5,
            maxDistanceMeters = 50_000f
        )

        result.map { it.id } shouldBeEqualTo listOf("biz-close")
    }

    @Test
    fun nearest_givenNoMaxDistance_expectFarRegionsStillIncluded() {
        val close = region("biz-close", 0.01, 0.0)
        val far = region("biz-far", 5.0, 0.0) // ~555 km

        // Default (no cap): distance doesn't exclude, only the count budget does.
        val result = filter.nearest(listOf(far, close), latitude = 0.0, longitude = 0.0, max = 5, maxDistanceMeters = noDistanceCap)

        result.map { it.id } shouldBeEqualTo listOf("biz-close", "biz-far")
    }

    @Test
    fun nearest_givenEquallyDistantRegions_expectOrderedByIdNotInputOrder() {
        // Equally distant either side of the origin, supplied in reverse id order: the id tiebreak
        // must decide, so the result can't depend on the server's response order.
        val second = region("biz-second", -1.0, 0.0)
        val first = region("biz-first", 1.0, 0.0)

        val result = filter.nearest(listOf(second, first), latitude = 0.0, longitude = 0.0, max = 2, maxDistanceMeters = noDistanceCap)

        result.map { it.id } shouldBeEqualTo listOf("biz-first", "biz-second")
    }

    @Test
    fun nearest_givenSubMeterDistanceDifference_expectRoundingLetsIdTiebreakDecide() {
        // Same centre, radii chosen so the boundary distances are 1000.4 m and 1000.2 m — a sub-meter
        // gap that rounds to the same whole meter. On raw distance "biz-b" would sort first; rounding
        // makes it a tie so the id decides, which is what keeps the order deterministic.
        val centreDistance = region("probe", 0.01, 0.0).distanceTo(0.0, 0.0)
        val a = region("biz-a", 0.01, 0.0, radius = centreDistance - 1000.4f)
        val b = region("biz-b", 0.01, 0.0, radius = centreDistance - 1000.2f)

        val result = filter.nearest(listOf(a, b), latitude = 0.0, longitude = 0.0, max = 2, maxDistanceMeters = noDistanceCap)

        result.map { it.id } shouldBeEqualTo listOf("biz-a", "biz-b")
    }

    @Test
    fun nearest_givenOccupiedRegionAndNearerCenteredRegions_expectOccupiedRegionKeptAndRankedFirst() {
        // Device sits inside a 5 km region ~2.2 km from its center, so ranking on center distance
        // would place it 4th and the count budget would evict it — leaving it unmonitored and its
        // exit unreportable.
        val occupied = region("biz-occupied", 0.02, 0.0, radius = 5_000f)
        val small1 = region("biz-small-1", 0.001, 0.0) // ~110 m center, ~10 m edge
        val small2 = region("biz-small-2", 0.002, 0.0) // ~221 m center, ~121 m edge
        val small3 = region("biz-small-3", 0.003, 0.0) // ~332 m center, ~232 m edge

        val result = filter.nearest(
            listOf(small1, small2, small3, occupied),
            latitude = 0.0,
            longitude = 0.0,
            max = 3,
            maxDistanceMeters = noDistanceCap
        )

        // The occupied region sorts first at edge distance 0; the farthest small region is evicted.
        result.map { it.id } shouldBeEqualTo listOf("biz-occupied", "biz-small-1", "biz-small-2")
    }

    @Test
    fun nearest_givenOccupiedRegionCenterBeyondDistanceCap_expectRegionStillIncluded() {
        // Center ~5.5 km away with an 8 km radius: the device is inside, but the center falls outside
        // the 3 km cap, so measuring to the center would filter out a region containing the device.
        val occupied = region("biz-occupied", 0.05, 0.0, radius = 8_000f)
        // Control: neither the center (~11 km) nor the boundary (~11 km) is within the cap.
        val beyond = region("biz-beyond", 0.1, 0.0)

        val result = filter.nearest(
            listOf(occupied, beyond),
            latitude = 0.0,
            longitude = 0.0,
            max = 5,
            maxDistanceMeters = 3_000f
        )

        result.map { it.id } shouldBeEqualTo listOf("biz-occupied")
    }

    @Test
    fun nearest_givenLargeRegionWithCloserBoundary_expectRankedAheadOfNearerCenteredSmallRegion() {
        // Applies to regions the device is outside too: a 2 km region centered ~2.2 km away has its
        // boundary ~211 m off, nearer than a 100 m region centered ~1.1 km away.
        val bigFar = region("biz-big-far", 0.02, 0.0, radius = 2_000f)
        val smallNear = region("biz-small-near", 0.01, 0.0)

        val result = filter.nearest(
            listOf(smallNear, bigFar),
            latitude = 0.0,
            longitude = 0.0,
            max = 2,
            maxDistanceMeters = noDistanceCap
        )

        result.map { it.id } shouldBeEqualTo listOf("biz-big-far", "biz-small-near")
    }

    @Test
    fun edgeDistanceToOrNull_givenPointInPolygonEnclosingCircleDeadSpace_expectUsesPolygonBoundaryDistance() {
        // The point sits inside the region's enclosing circle but in the polygon's concave dead
        // space: measuring against the circle would call it "inside" (distance 0).
        concaveRegion().edgeDistanceToOrNull(2.0, 2.0)!! shouldBeGreaterThan 100_000f
    }

    // ---------- polygons never reach the registered set from this build ----------

    @Test
    fun nearest_givenPolygonRegionAndPolygonMonitoringDisabled_expectDroppedAndCirclesKept() {
        // Ranking is the last gate before registration. Without a polygon runtime the region has no
        // safe interpretation — its circle fields are the coarse trigger — so it is skipped, while
        // the rest of the catalog ranks normally.
        val circle = region("biz-circle", 1.4, 1.4)

        val result = filter.nearest(
            listOf(concaveRegion(), circle),
            latitude = 1.4,
            longitude = 1.4,
            max = 5,
            maxDistanceMeters = noDistanceCap
        )

        result.map { it.id } shouldBeEqualTo listOf("biz-circle")
    }

    @Test
    fun nearest_givenPolygonRegionAndPolygonMonitoringEnabled_expectRankedByPolygonBoundary() {
        // The same input with the opt-in supplied ranks on the ring, proving the drop above is the
        // opt-in and not a missing capability.
        val enabled = GeofenceDistanceFilter(polygonSupport = EnabledPolygonSupport)

        val result = enabled.nearest(
            listOf(concaveRegion()),
            latitude = 0.5,
            longitude = 0.5,
            max = 5,
            maxDistanceMeters = noDistanceCap
        )

        result.map { it.id } shouldBeEqualTo listOf("concave")
    }

    @Test
    fun nearest_givenCachedPolygonRingThatFailsValidation_expectRegionDroppedWithoutFailingTheRest() {
        // A ring corrupted in the cache must not throw out of ranking (which would strand the whole
        // catalog) and must not silently degrade into its circle fields.
        val enabled = GeofenceDistanceFilter(polygonSupport = EnabledPolygonSupport)
        val broken = GeofenceRegion(
            id = "broken-polygon",
            latitude = 0.0,
            longitude = 0.0,
            radius = 100_000f,
            polygonVertices = listOf(
                PolygonCoordinate(-0.001, -0.001),
                PolygonCoordinate(0.001, 0.001),
                PolygonCoordinate(-0.001, 0.001),
                PolygonCoordinate(0.001, -0.001)
            )
        )
        val circle = region("biz-circle", 0.0, 0.0)

        val result = enabled.nearest(
            listOf(broken, circle),
            latitude = 0.0,
            longitude = 0.0,
            max = 5,
            maxDistanceMeters = noDistanceCap
        )

        result.map { it.id } shouldBeEqualTo listOf("biz-circle")
    }

    @Test
    fun nearest_givenRepeatedPassesOverUnchangedCatalog_expectRingValidatedOnce() {
        // Ring validation is O(V²) and ranking re-runs on every movement trigger, so the result is
        // memoized per id + ring rather than recomputed per pass.
        val enabled = GeofenceDistanceFilter(polygonSupport = EnabledPolygonSupport)
        val regions = listOf(concaveRegion())
        mockkObject(PolygonGeometry.Companion)
        try {
            repeat(3) {
                enabled.nearest(regions, latitude = 0.5, longitude = 0.5, max = 5, maxDistanceMeters = noDistanceCap)
            }

            verify(exactly = 1) { PolygonGeometry.fromOrNull(any()) }
        } finally {
            unmockkObject(PolygonGeometry.Companion)
        }
    }

    // Concave L-shape whose enclosing circle covers a large area the polygon excludes.
    private fun concaveRegion() = GeofenceRegion(
        id = "concave",
        latitude = 1.5,
        longitude = 1.5,
        radius = 300_000f,
        polygonVertices = listOf(
            PolygonCoordinate(0.0, 0.0),
            PolygonCoordinate(0.0, 3.0),
            PolygonCoordinate(1.0, 3.0),
            PolygonCoordinate(1.0, 1.0),
            PolygonCoordinate(3.0, 1.0),
            PolygonCoordinate(3.0, 0.0)
        )
    )

    private fun region(
        id: String,
        latitude: Double,
        longitude: Double,
        radius: Float = 100f
    ) = GeofenceRegion(id = id, latitude = latitude, longitude = longitude, radius = radius)
}
