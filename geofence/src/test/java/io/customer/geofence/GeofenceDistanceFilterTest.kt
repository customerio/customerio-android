package io.customer.geofence

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.polygon.PolygonCoordinate
import io.customer.geofence.polygon.PolygonGeometry
import io.customer.geofence.polygon.PolygonSupport
import io.mockk.mockk
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

        val result = filter.nearest(listOf(far, close), latitude = 0.0, longitude = 0.0, max = 5, maxDistanceMeters = noDistanceCap)

        result.map { it.id } shouldBeEqualTo listOf("biz-close", "biz-far")
    }

    @Test
    fun nearest_givenEquallyDistantRegions_expectOrderedByIdNotInputOrder() {
        // Equidistant, supplied in reverse id order.
        val second = region("biz-second", -1.0, 0.0)
        val first = region("biz-first", 1.0, 0.0)

        val result = filter.nearest(listOf(second, first), latitude = 0.0, longitude = 0.0, max = 2, maxDistanceMeters = noDistanceCap)

        result.map { it.id } shouldBeEqualTo listOf("biz-first", "biz-second")
    }

    @Test
    fun nearest_givenSubMeterDistanceDifference_expectRoundingLetsIdTiebreakDecide() {
        // Boundary distances 1000.4 m and 1000.2 m round to the same meter; on raw distance "biz-b"
        // would sort first.
        val centreDistance = region("probe", 0.01, 0.0).distanceTo(0.0, 0.0)
        val a = region("biz-a", 0.01, 0.0, radius = centreDistance - 1000.4f)
        val b = region("biz-b", 0.01, 0.0, radius = centreDistance - 1000.2f)

        val result = filter.nearest(listOf(a, b), latitude = 0.0, longitude = 0.0, max = 2, maxDistanceMeters = noDistanceCap)

        result.map { it.id } shouldBeEqualTo listOf("biz-a", "biz-b")
    }

    @Test
    fun nearest_givenOccupiedRegionAndNearerCenteredRegions_expectOccupiedRegionKeptAndRankedFirst() {
        // Inside a 5 km region ~2.2 km from its center; center ranking would place it 4th and evict it.
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

        result.map { it.id } shouldBeEqualTo listOf("biz-occupied", "biz-small-1", "biz-small-2")
    }

    @Test
    fun nearest_givenOccupiedRegionCenterBeyondDistanceCap_expectRegionStillIncluded() {
        // Center ~5.5 km away, 8 km radius: the device is inside, but the center is beyond the 3 km cap.
        val occupied = region("biz-occupied", 0.05, 0.0, radius = 8_000f)
        // Control: ~11 km out, beyond the cap either way.
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
        // Boundary ~211 m off, nearer than the 100 m region centered ~1.1 km away.
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
    fun edgeDistanceToOrNull_givenPointInWakeCircleDeadSpace_expectUsesPolygonBoundaryDistance() {
        polygonRegion().edgeDistanceToOrNull(2.0, 2.0)!! shouldBeGreaterThan 100_000f
    }

    // ---------- polygons never reach the registered set from this build ----------

    @Test
    fun nearest_givenPolygonRegionAndPolygonMonitoringDisabled_expectDroppedAndCirclesKept() {
        // Its circle fields are only the coarse trigger, so without a polygon runtime it is skipped.
        val circle = region("biz-circle", 1.4, 1.4)

        val result = filter.nearest(
            listOf(polygonRegion(), circle),
            latitude = 1.4,
            longitude = 1.4,
            max = 5,
            maxDistanceMeters = noDistanceCap
        )

        result.map { it.id } shouldBeEqualTo listOf("biz-circle")
    }

    @Test
    fun nearest_givenPolygonRegionAndPolygonMonitoringEnabled_expectRankedByPolygonBoundary() {
        val enabled = GeofenceDistanceFilter(polygonSupport = PolygonSupport.Enabled)

        val result = enabled.nearest(
            listOf(polygonRegion()),
            latitude = 0.5,
            longitude = 0.5,
            max = 5,
            maxDistanceMeters = noDistanceCap
        )

        result.map { it.id } shouldBeEqualTo listOf("polygon")
    }

    @Test
    fun nearest_givenCachedPolygonRingThatFailsValidation_expectRegionDroppedWithoutFailingTheRest() {
        // Self-intersecting ring; it must not degrade into its circle fields either.
        val enabled = GeofenceDistanceFilter(polygonSupport = PolygonSupport.Enabled)
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
        val enabled = GeofenceDistanceFilter(polygonSupport = PolygonSupport.Enabled)
        val regions = listOf(polygonRegion())
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

    // ---------- a polygon the OS is monitoring is never evicted ----------

    @Test
    fun nearest_givenDeviceInsideAPolygonsWakeCircleAndRingBeyondTheCap_expectKept() {
        // Inside the wake circle, so the OS owes an EXIT; only the caller knows to pin it.
        val enabled = GeofenceDistanceFilter(polygonSupport = PolygonSupport.Enabled)

        val result = enabled.nearest(
            regions = listOf(polygonRegion()),
            latitude = 2.0,
            longitude = 2.0,
            max = 5,
            maxDistanceMeters = 50_000f,
            pinnedIds = setOf("polygon")
        )

        result.map { it.id } shouldBeEqualTo listOf("polygon")
    }

    @Test
    fun nearest_givenDeviceOutsideAPolygonsWakeCircle_expectDroppedByTheCap() {
        val enabled = GeofenceDistanceFilter(polygonSupport = PolygonSupport.Enabled)

        val result = enabled.nearest(
            regions = listOf(polygonRegion()),
            latitude = 10.0,
            longitude = 10.0,
            max = 5,
            maxDistanceMeters = 50_000f
        )

        result.shouldBeEmpty()
    }

    @Test
    fun nearest_givenDeviceInsideAPolygonsWakeCircleAndNoSlotsLeft_expectKeptAheadOfANearerCircle() {
        val enabled = GeofenceDistanceFilter(polygonSupport = PolygonSupport.Enabled)
        val nearby = region("biz-near", 2.0001, 2.0)

        val result = enabled.nearest(
            regions = listOf(nearby, polygonRegion()),
            latitude = 2.0,
            longitude = 2.0,
            max = 1,
            maxDistanceMeters = noDistanceCap,
            pinnedIds = setOf("polygon")
        )

        result.map { it.id } shouldBeEqualTo listOf("polygon")
    }

    // ---------- pinned regions survive discovery caps so their EXIT can still be observed ----------

    @Test
    fun nearest_givenPinnedPolygonBeyondDistanceCap_expectRetainedAheadOfNearbyCircle() {
        val pinned = region("pinned", 10.0, 0.0)
        val nearby = region("nearby", 0.01, 0.0)

        val result = filter.nearest(
            regions = listOf(nearby, pinned),
            latitude = 0.0,
            longitude = 0.0,
            max = 1,
            maxDistanceMeters = 1_000f,
            pinnedIds = setOf("pinned")
        )

        result.map(GeofenceRegion::id) shouldBeEqualTo listOf("pinned")
    }

    @Test
    fun nearest_givenMorePinnedPolygonsThanPositiveCap_expectAllPinnedRetainedForExitMonitoring() {
        val result = filter.nearest(
            regions = listOf(
                region("nearby", 0.01, 0.0),
                region("active-a", 10.0, 0.0),
                region("active-b", 11.0, 0.0)
            ),
            latitude = 0.0,
            longitude = 0.0,
            max = 1,
            maxDistanceMeters = 1_000f,
            pinnedIds = setOf("active-a", "active-b")
        )

        result.map(GeofenceRegion::id) shouldBeEqualTo listOf("active-a", "active-b")
    }

    // ---------- pinning outranks the server cap, never the OS ceiling ----------

    @Test
    fun nearest_givenMorePinnedRegionsThanOsBusinessSlots_expectFarthestReleasedAndLogged() {
        // GMS rejects the whole batch past its limit, so the farthest pins are released first.
        val logger: GeofenceLogger = mockk(relaxed = true)
        val slotLimited = GeofenceDistanceFilter(maxOsBusinessSlots = 2, logger = logger)
        val regions = listOf(
            region("pinned-far", 12.0, 0.0),
            region("pinned-near", 10.0, 0.0),
            region("pinned-mid", 11.0, 0.0),
            region("nearby", 0.01, 0.0)
        )

        val result = slotLimited.nearest(
            regions = regions,
            latitude = 0.0,
            longitude = 0.0,
            max = 50,
            maxDistanceMeters = noDistanceCap,
            pinnedIds = setOf("pinned-near", "pinned-mid", "pinned-far")
        )

        result.map(GeofenceRegion::id) shouldBeEqualTo listOf("pinned-near", "pinned-mid")
        verify { logger.logPinnedRegionDroppedAtOsLimit("pinned-far", 2) }
    }

    @Test
    fun nearest_givenPinsFillingEverySlot_expectNoDiscoveryEvenBelowServerCap() {
        val slotLimited = GeofenceDistanceFilter(maxOsBusinessSlots = 2, logger = mockk(relaxed = true))

        val result = slotLimited.nearest(
            regions = listOf(
                region("nearby-a", 0.01, 0.0),
                region("nearby-b", 0.02, 0.0),
                region("pinned-a", 10.0, 0.0),
                region("pinned-b", 11.0, 0.0)
            ),
            latitude = 0.0,
            longitude = 0.0,
            max = 50,
            maxDistanceMeters = noDistanceCap,
            pinnedIds = setOf("pinned-a", "pinned-b")
        )

        result.map(GeofenceRegion::id) shouldBeEqualTo listOf("pinned-a", "pinned-b")
    }

    @Test
    fun nearest_givenNoPins_expectResultStillBoundedByOsBusinessSlots() {
        val slotLimited = GeofenceDistanceFilter(maxOsBusinessSlots = 2, logger = mockk(relaxed = true))
        val regions = (1..5).map { region("biz-$it", it * 0.01, 0.0) }

        val result = slotLimited.nearest(
            regions = regions,
            latitude = 0.0,
            longitude = 0.0,
            max = 50,
            maxDistanceMeters = noDistanceCap
        )

        result.map(GeofenceRegion::id) shouldBeEqualTo listOf("biz-1", "biz-2")
    }

    @Test
    fun nearest_givenDefaultConstruction_expectLeavesOneOsSlotForTheMovementTrigger() {
        // The repository prepends the movement trigger, so one OS slot must stay free.
        val regions = (1..GeofenceConstants.MAX_OS_GEOFENCES + 20).map {
            region("biz-%03d".format(it), it * 0.001, 0.0)
        }

        val result = filter.nearest(
            regions = regions,
            latitude = 0.0,
            longitude = 0.0,
            max = Int.MAX_VALUE,
            maxDistanceMeters = noDistanceCap,
            pinnedIds = regions.map(GeofenceRegion::id).toSet()
        )

        result.size shouldBeEqualTo GeofenceConstants.MAX_OS_GEOFENCES - 1
        (result.size + 1) shouldBeEqualTo GeofenceConstants.MAX_OS_GEOFENCES
    }

    @Test
    fun nearest_givenUnpinnedPolygonsContainingTheFix_expectTheCapStillBoundsDiscovery() {
        // Only a registered region can owe an EXIT, so an unpinned polygon competes for the cap.
        val enabled = GeofenceDistanceFilter(polygonSupport = PolygonSupport.Enabled)

        val result = enabled.nearest(
            regions = listOf(
                region("inside-me", 0.0, 0.0),
                wakeCirclePolygon("poly-a", 0.003),
                wakeCirclePolygon("poly-b", 0.004)
            ),
            latitude = 0.0,
            longitude = 0.0,
            max = 1,
            maxDistanceMeters = noDistanceCap
        )

        result.map(GeofenceRegion::id) shouldBeEqualTo listOf("inside-me")
    }

    @Test
    fun nearest_givenAPinnedPolygonContainingTheFix_expectStillRetainedPastTheCap() {
        val enabled = GeofenceDistanceFilter(polygonSupport = PolygonSupport.Enabled)

        val result = enabled.nearest(
            regions = listOf(
                region("inside-me", 0.0, 0.0),
                wakeCirclePolygon("poly-a", 0.003)
            ),
            latitude = 0.0,
            longitude = 0.0,
            max = 1,
            maxDistanceMeters = noDistanceCap,
            pinnedIds = setOf("poly-a")
        )

        result.map(GeofenceRegion::id) shouldBeEqualTo listOf("poly-a")
    }

    // A polygon whose wake circle contains (0, 0) while its ring sits a few hundred metres away.
    private fun wakeCirclePolygon(id: String, latitude: Double) = GeofenceRegion(
        id = id,
        latitude = latitude,
        longitude = 0.0,
        radius = 1_000f,
        polygonVertices = listOf(
            PolygonCoordinate(latitude - 0.0005, -0.0005),
            PolygonCoordinate(latitude - 0.0005, 0.0005),
            PolygonCoordinate(latitude + 0.0005, 0.0005),
            PolygonCoordinate(latitude + 0.0005, -0.0005)
        )
    )

    // Convex square whose backend wake circle covers a larger area than the business boundary.
    private fun polygonRegion() = GeofenceRegion(
        id = "polygon",
        latitude = 1.5,
        longitude = 1.5,
        radius = 300_000f,
        polygonVertices = listOf(
            PolygonCoordinate(0.0, 0.0),
            PolygonCoordinate(0.0, 1.0),
            PolygonCoordinate(1.0, 1.0),
            PolygonCoordinate(1.0, 0.0)
        )
    )

    private fun region(
        id: String,
        latitude: Double,
        longitude: Double,
        radius: Float = 100f
    ) = GeofenceRegion(id = id, latitude = latitude, longitude = longitude, radius = radius)
}
