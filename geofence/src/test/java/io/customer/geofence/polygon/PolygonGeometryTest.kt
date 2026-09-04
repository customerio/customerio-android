package io.customer.geofence.polygon

import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInRange
import org.amshove.kluent.shouldThrow
import org.junit.Test

class PolygonGeometryTest {
    @Test
    fun from_whenPolygonIsConcave_thenAcceptsAndAnswersInsideTheNotch() {
        // Concavity is the backend's call, not ours. What matters is that the ray cast still gets
        // the reflex corner right: the notch of an L is outside, both arms are inside.
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
    fun relationTo_whenPointIsNearlyAntipodalToTheRing_thenReturnsOutside() {
        // Ordinary ring on the prime meridian, query half a world away. Mapping each longitude onto
        // the query point instead of onto the ring splits this ring across the wrap boundary, and the
        // seam edge becomes a 358-degree chord that swallows the globe.
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
        // Circles at the pole were always registerable; rejecting a polygon there was the SDK
        // holding the payload to a stricter rule than the backend or the rest of the module.
        val nearPole = PolygonGeometry.from(
            listOf(
                point(89.92, 10.0),
                point(89.86, 11.0),
                point(89.92, 12.0)
            )
        )

        nearPole.vertices.size shouldBeEqualTo 3
    }

    /** Two-degree square on the prime meridian: an unremarkable ring, nowhere near the seam. */
    private fun primeMeridian(): PolygonGeometry = PolygonGeometry.from(
        listOf(
            point(0.0, -1.0),
            point(0.0, 1.0),
            point(1.0, 1.0),
            point(1.0, -1.0)
        )
    )

    /** One-degree square straddling the antimeridian: longitude runs 179.5 east to -179.5 west. */
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
}
