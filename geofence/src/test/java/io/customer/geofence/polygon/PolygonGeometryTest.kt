package io.customer.geofence.polygon

import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInRange
import org.amshove.kluent.shouldThrow
import org.junit.Test

class PolygonGeometryTest {
    @Test
    fun from_whenPolygonIsConcave_thenAcceptsAndAnswersInsideTheNotch() {
        // Concavity is the backend's call; the ray cast must still get the L's reflex corner right.
        val lShape = PolygonGeometry.from(
            listOf(
                point(0.0, 0.0),
                point(0.0, 3.0),
                point(1.0, 3.0),
                point(1.0, 1.0),
                point(3.0, 1.0),
                point(3.0, 0.0)
            )
        )

        lShape.relationTo(point(2.0, 2.0)) shouldBeEqualTo PolygonPointRelation.OUTSIDE
        lShape.relationTo(point(0.5, 2.5)) shouldBeEqualTo PolygonPointRelation.INSIDE
        lShape.relationTo(point(2.5, 0.5)) shouldBeEqualTo PolygonPointRelation.INSIDE
    }

    @Test
    fun relationTo_whenPointIsOnEdge_thenReturnsBoundary() {
        val geometry = square()

        geometry.relationTo(point(0.0, 0.5)) shouldBeEqualTo PolygonPointRelation.BOUNDARY
    }

    @Test
    fun from_whenGeoJsonRingRepeatsFirstVertex_thenCanonicalizesClosingVertex() {
        val vertices = listOf(
            point(0.0, 0.0),
            point(0.0, 1.0),
            point(1.0, 1.0),
            point(1.0, 0.0),
            point(0.0, 0.0)
        )

        PolygonGeometry.from(vertices).vertices.size shouldBeEqualTo 4
    }

    @Test
    fun from_whenRingHasFewerThanThreeDistinctVertices_thenRejectsGeometry() {
        invoking {
            PolygonGeometry.from(
                listOf(point(0.0, 0.0), point(1.0, 1.0), point(0.0, 0.0))
            )
        } shouldThrow IllegalArgumentException::class
    }

    @Test
    fun from_whenSegmentCrossesAntimeridian_thenAcceptsGeometry() {
        PolygonGeometry.from(
            listOf(point(0.0, 179.0), point(1.0, -179.0), point(2.0, 179.0))
        ).vertices.size shouldBeEqualTo 3
    }

    @Test
    fun relationTo_whenRingCrossesAntimeridianAndPointIsWithin_thenReturnsInside() {
        dateline().relationTo(point(0.5, 180.0)) shouldBeEqualTo PolygonPointRelation.INSIDE
    }

    @Test
    fun relationTo_whenRingCrossesAntimeridianAndPointIsBeyondIt_thenReturnsOutside() {
        // A degree west of the ring's western edge. Read with raw longitudes this point sorts
        // *between* the ring's own bounds, so an unwrapped ray cast reports it as inside.
        dateline().relationTo(point(0.5, 178.5)) shouldBeEqualTo PolygonPointRelation.OUTSIDE
    }

    @Test
    fun relationTo_whenPointLiesOnAnAntimeridianCrossingEdge_thenReturnsBoundary() {
        dateline().relationTo(point(0.0, 180.0)) shouldBeEqualTo PolygonPointRelation.BOUNDARY
    }

    @Test
    fun from_whenSimpleRingIsWrittenWithNegativeSeamLongitudes_thenAcceptsGeometry() {
        // The seam vertex is written -180 rather than 180, both legal GeoJSON. Judged on raw
        // longitudes that edge reads as a ~359-degree chord and the ring as self-intersecting.
        PolygonGeometry.from(
            listOf(point(-2.0, 179.0), point(-2.0, 179.5), point(-1.0, -180.0), point(-1.0, 179.0))
        ).vertices.size shouldBeEqualTo 4
    }

    @Test
    fun from_whenRingIsClosedWithTheOppositeSeamSign_thenCanonicalizesClosingVertex() {
        // -180 and 180 are the same position. Compared raw the ring never looks closed, so the
        // closing vertex survives and its zero-length edge gets rejected.
        PolygonGeometry.from(
            listOf(point(0.0, -180.0), point(1.0, -179.0), point(-1.0, -179.0), point(0.0, 180.0))
        ).vertices.size shouldBeEqualTo 3
    }

    @Test
    fun from_whenClosedRingUsesDecimalCoordinates_thenCanonicalizesClosingVertex() {
        // Unwrapping lands the closing position ~1e-13 from the one it repeats. Compared exactly,
        // the ring never looks closed and the surviving sliver edge reads as a self-intersection.
        PolygonGeometry.from(
            listOf(
                point(37.0, -122.1),
                point(37.0, -122.1002),
                point(37.0001, -122.1003),
                point(37.0, -122.1)
            )
        ).vertices.size shouldBeEqualTo 3
    }

    @Test
    fun from_whenRingWindsAroundAPole_thenRejectsUnevaluableGeometry() {
        // Simple and non-degenerate, but it unwraps across 270 degrees. On one flat frame the
        // evaluator would answer against a closing chord most of the way round the earth.
        invoking {
            PolygonGeometry.from(
                listOf(point(80.0, 0.0), point(82.0, 90.0), point(82.0, 180.0), point(82.0, -90.0))
            )
        } shouldThrow IllegalArgumentException::class
    }

    @Test
    fun from_whenRingCrossingTheSeamIsSelfIntersecting_thenStillRejectsGeometry() {
        // Seam unwrapping must not exempt a seam-crossing ring from the self-intersection check.
        invoking {
            PolygonGeometry.from(
                listOf(point(0.0, 179.5), point(1.0, -179.5), point(1.0, 179.5), point(0.0, -179.5))
            )
        } shouldThrow IllegalArgumentException::class
    }

    @Test
    fun relationTo_whenPointIsNearlyAntipodalToTheRing_thenReturnsOutside() {
        // Mapping longitudes onto the query point instead of the ring would split this ring across
        // the wrap, and its seam edge would become a 358-degree chord that swallows the globe.
        val ring = primeMeridian()

        ring.relationTo(point(0.5, 180.0)) shouldBeEqualTo PolygonPointRelation.OUTSIDE
        ring.relationTo(point(0.5, -179.0)) shouldBeEqualTo PolygonPointRelation.OUTSIDE
    }

    @Test
    fun boundaryDistanceMeters_whenPointIsNearlyAntipodalToTheRing_thenMeasuresHalfTheGlobe() {
        primeMeridian().boundaryDistanceMeters(point(0.5, 180.0)) shouldBeInRange 19_000_000.0..20_100_000.0
    }

    @Test
    fun boundaryDistanceMeters_whenRingCrossesAntimeridian_thenMeasuresAcrossTheSeam() {
        // ~0.5 degrees of longitude from the eastern edge at the equator, not ~359.5.
        val distance = dateline().boundaryDistanceMeters(point(0.5, 179.0))

        distance shouldBeInRange 50_000.0..60_000.0
    }

    @Test
    fun from_whenPolygonIntersectsItself_thenRejectsGeometry() {
        invoking {
            PolygonGeometry.from(
                listOf(
                    point(0.0, 0.0),
                    point(1.0, 1.0),
                    point(0.0, 1.0),
                    point(1.0, 0.0)
                )
            )
        } shouldThrow IllegalArgumentException::class
    }

    @Test
    fun from_whenPolygonHasZeroArea_thenRejectsGeometry() {
        invoking {
            PolygonGeometry.from(
                listOf(point(0.0, 0.0), point(1.0, 1.0), point(2.0, 2.0))
            )
        } shouldThrow IllegalArgumentException::class
    }

    @Test
    fun from_whenPolygonApproachesGeographicPole_thenAccepted() {
        // Circles at the pole are registerable, so a polygon there is not held to a stricter rule.
        val nearPole = PolygonGeometry.from(
            listOf(
                point(89.92, 10.0),
                point(89.86, 11.0),
                point(89.92, 12.0)
            )
        )

        nearPole.vertices.size shouldBeEqualTo 3
    }

    private fun primeMeridian(): PolygonGeometry = PolygonGeometry.from(
        listOf(
            point(0.0, -1.0),
            point(0.0, 1.0),
            point(1.0, 1.0),
            point(1.0, -1.0)
        )
    )

    private fun dateline(): PolygonGeometry = PolygonGeometry.from(
        listOf(
            point(0.0, 179.5),
            point(0.0, -179.5),
            point(1.0, -179.5),
            point(1.0, 179.5)
        )
    )

    private fun square(): PolygonGeometry = PolygonGeometry.from(
        listOf(
            point(0.0, 0.0),
            point(0.0, 1.0),
            point(1.0, 1.0),
            point(1.0, 0.0)
        )
    )

    private fun point(latitude: Double, longitude: Double) =
        PolygonCoordinate(latitude = latitude, longitude = longitude)

    /**
     * Pins R=6,371,000 m, shared with iOS: 111,195 m per degree vs 111,320 m at the WGS84
     * equatorial radius. That ~0.11% decides whether a tightly fitting polygon validates.
     */
    @Test
    fun boundaryDistanceMeters_givenOneDegreeOfLatitude_expectTheSphericalRadiusScale() {
        val square = PolygonGeometry.from(
            listOf(
                PolygonCoordinate(-0.0001, -0.0001),
                PolygonCoordinate(-0.0001, 0.0001),
                PolygonCoordinate(0.0001, 0.0001),
                PolygonCoordinate(0.0001, -0.0001)
            )
        )

        val oneDegreeNorth = square.boundaryDistanceMeters(PolygonCoordinate(1.0, 0.0))

        // Tolerance is far tighter than the 124 m the WGS84 radius would add, and far looser than
        // the 11 m the tiny square's own half-width contributes.
        oneDegreeNorth shouldBeInRange 111_150.0..111_240.0
    }
}
