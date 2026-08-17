package io.customer.geofence

import com.google.android.gms.location.Geofence
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.polygon.PolygonCoordinate
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotContain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceRegionTest : RobolectricTest() {

    @Test
    fun toGmsTransitionTypes_givenEnterOnly_expectEnterBitmask() {
        val region = buildRegion(transitionTypes = listOf(GeofenceTransitionType.ENTER))

        region.toGmsTransitionTypes() shouldBeEqualTo Geofence.GEOFENCE_TRANSITION_ENTER
    }

    @Test
    fun toGmsTransitionTypes_givenExitOnly_expectExitBitmask() {
        val region = buildRegion(transitionTypes = listOf(GeofenceTransitionType.EXIT))

        region.toGmsTransitionTypes() shouldBeEqualTo Geofence.GEOFENCE_TRANSITION_EXIT
    }

    @Test
    fun toGmsTransitionTypes_givenBothTransitions_expectCombinedBitmask() {
        val region = buildRegion(
            transitionTypes = listOf(GeofenceTransitionType.ENTER, GeofenceTransitionType.EXIT)
        )

        region.toGmsTransitionTypes() shouldBeEqualTo
            (Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
    }

    @Test
    fun toGmsTransitionTypes_givenDefaultTransitions_expectBothEnterAndExit() {
        val region = buildRegion()

        region.toGmsTransitionTypes() shouldBeEqualTo
            (Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
    }

    @Test
    fun edgeDistanceToOrNull_givenCoordinatesInsideRegion_expectZero() {
        val region = buildRegion(radius = 5_000f)

        // ~2.2 km from center, well within the radius.
        region.edgeDistanceToOrNull(0.02, 0.0) shouldBeEqualTo 0f
    }

    @Test
    fun edgeDistanceToOrNull_givenCoordinatesOnBoundary_expectZero() {
        val centerDistance = buildRegion().distanceTo(0.01, 0.0)
        val region = buildRegion(radius = centerDistance)

        region.edgeDistanceToOrNull(0.01, 0.0) shouldBeEqualTo 0f
    }

    @Test
    fun edgeDistanceToOrNull_givenCoordinatesOutsideRegion_expectDistanceLessRadius() {
        val region = buildRegion(radius = 1_000f)
        val centerDistance = region.distanceTo(0.05, 0.0)

        region.edgeDistanceToOrNull(0.05, 0.0) shouldBeEqualTo centerDistance - 1_000f
    }

    // ---------- circle cache compatibility ----------

    @Test
    fun encode_givenCircleRegion_expectNoPolygonFieldOnTheWire() {
        // The polygon field defaults to null and defaults aren't encoded, so a circle's cached JSON
        // is byte-identical to what previous SDK versions wrote and read.
        val json = GeofenceJsonSerializer().encode(GeofenceRegion.serializer(), buildRegion())

        json shouldNotContain "polygonVertices"
    }

    @Test
    fun decode_givenCacheWrittenBeforePolygons_expectCircleUnchanged() {
        val legacyJson = """{"id":"biz-1","latitude":1.0,"longitude":2.0,"radius":100.0}"""

        val region = GeofenceJsonSerializer().decode(GeofenceRegion.serializer(), legacyJson)

        region.isPolygon shouldBeEqualTo false
        region.polygonVertices.shouldBeNull()
        region.edgeDistanceToOrNull(1.0, 2.0) shouldBeEqualTo 0f
    }

    // ---------- polygon regions with unusable geometry ----------
    //
    // A cached region can outlive the ring that produced it (a corrupted file, a downgrade). Every
    // accessor must answer "unknown" rather than fall back to the circle fields, which hold the
    // coarse enclosing trigger — kilometres wider than the fence.

    @Test
    fun polygonGeometryOrNull_givenRingThatFailsValidation_expectNullNotThrow() {
        buildPolygonRegion(selfIntersectingRing()).polygonGeometryOrNull().shouldBeNull()
    }

    @Test
    fun edgeDistanceToOrNull_givenRingThatFailsValidation_expectNull() {
        buildPolygonRegion(selfIntersectingRing()).edgeDistanceToOrNull(0.0, 0.0).shouldBeNull()
    }

    @Test
    fun edgeDistanceToOrNull_givenValidRingAndPointInside_expectZero() {
        buildPolygonRegion(squareRing()).edgeDistanceToOrNull(0.0, 0.0) shouldBeEqualTo 0f
    }

    @Test
    fun contains_givenRingThatFailsValidation_expectFalseEvenInsideTriggerCircle() {
        // The fix is dead-centre in the region's circle fields; without usable geometry the answer
        // must still be "not inside", or a synthesized ENTER would fire for the trigger circle.
        buildPolygonRegion(selfIntersectingRing()).contains(0.0, 0.0) shouldBeEqualTo false
    }

    @Test
    fun contains_givenValidRing_expectShapeAnswer() {
        val region = buildPolygonRegion(squareRing())

        region.contains(0.0, 0.0) shouldBeEqualTo true
        region.contains(0.5, 0.5) shouldBeEqualTo false
    }

    private fun buildPolygonRegion(vertices: List<PolygonCoordinate>) = GeofenceRegion(
        id = "polygon-geofence",
        latitude = 0.0,
        longitude = 0.0,
        radius = 100_000f,
        polygonVertices = vertices
    )

    private fun squareRing() = listOf(
        PolygonCoordinate(-0.001, -0.001),
        PolygonCoordinate(-0.001, 0.001),
        PolygonCoordinate(0.001, 0.001),
        PolygonCoordinate(0.001, -0.001)
    )

    // Bow-tie: valid coordinates, but the ring crosses itself so it has no well-defined inside.
    private fun selfIntersectingRing() = listOf(
        PolygonCoordinate(-0.001, -0.001),
        PolygonCoordinate(0.001, 0.001),
        PolygonCoordinate(-0.001, 0.001),
        PolygonCoordinate(0.001, -0.001)
    )

    private fun buildRegion(
        transitionTypes: List<GeofenceTransitionType> = listOf(
            GeofenceTransitionType.ENTER,
            GeofenceTransitionType.EXIT
        ),
        radius: Float = 100f
    ) = GeofenceRegion(
        id = "test-geofence",
        latitude = 0.0,
        longitude = 0.0,
        radius = radius,
        transitionTypes = transitionTypes
    )
}
