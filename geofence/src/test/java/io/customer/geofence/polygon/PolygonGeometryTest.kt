package io.customer.geofence.polygon

import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
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
    fun from_whenSegmentCrossesAntimeridian_thenRejectsGeometry() {
        invoking {
            PolygonGeometry.from(
                listOf(point(0.0, 179.0), point(1.0, -179.0), point(2.0, 179.0))
            )
        } shouldThrow IllegalArgumentException::class
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
    fun from_whenPolygonApproachesGeographicPole_thenRejectsUnsupportedGeometry() {
        invoking {
            PolygonGeometry.from(
                listOf(
                    point(89.92593569795733, -145.18356655630183),
                    point(89.8623631437237, 16.93650091099056),
                    point(89.9222388615163, 60.8667321459084)
                )
            )
        } shouldThrow IllegalArgumentException::class
    }

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
