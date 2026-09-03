package io.customer.geofence.polygon

import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.Test

class PolygonWakeCircleValidatorTest {
    private val geometry = PolygonGeometry.from(
        listOf(
            point(37.0, -122.0),
            point(37.0, -121.999),
            point(37.001, -121.999),
            point(37.001, -122.0)
        )
    )

    @Test
    fun prepare_givenBackendCircleContainingPolygon_expectPlatformMarginAppliedWithoutRecentering() {
        val wakeCircle = PolygonWakeCircle(
            center = point(37.0005, -121.9995),
            baseRadiusMeters = 100.0
        )

        val trigger = PolygonWakeCircleValidator().prepare(geometry, wakeCircle)

        trigger.center shouldBeEqualTo wakeCircle.center
        trigger.radiusMeters shouldBeEqualTo 1_100f
    }

    @Test
    fun prepare_givenVertexExactlyOnALargeBackendCircle_expectAccepted() {
        // The case the fixed 1 m tolerance rejected: a sphere measures a vertex on a backend-valid
        // circle slightly long, and the overshoot grows with the radius. 1,004 m against a 1,000 m
        // circle is inside the 0.5% slack but well outside a flat metre.
        val center = point(37.0, -122.0)
        val justOutside = point(37.0 + METRES_1004 / METRES_PER_DEGREE_LATITUDE, -122.0)
        val ring = PolygonGeometry.from(
            listOf(justOutside, point(36.995, -122.005), point(36.995, -121.995))
        )

        val trigger = PolygonWakeCircleValidator().prepare(
            ring,
            PolygonWakeCircle(center = center, baseRadiusMeters = 1_000.0)
        )

        trigger.radiusMeters shouldBeEqualTo 2_000f
    }

    @Test
    fun prepare_givenVertexWellBeyondTheProportionalTolerance_expectRejected() {
        // The tolerance scales, but it must stay a tolerance: 5% out is a circle that genuinely
        // does not contain the ring.
        val center = point(37.0, -122.0)
        val farNorth = point(37.0 + METRES_1050 / METRES_PER_DEGREE_LATITUDE, -122.0)
        val ring = PolygonGeometry.from(
            listOf(farNorth, point(36.995, -122.005), point(36.995, -121.995))
        )

        invoking {
            PolygonWakeCircleValidator().prepare(
                ring,
                PolygonWakeCircle(center = center, baseRadiusMeters = 1_000.0)
            )
        } shouldThrow IllegalArgumentException::class
    }

    @Test
    fun prepare_givenBackendCircleThatMissesVertex_expectRejected() {
        val wakeCircle = PolygonWakeCircle(
            center = point(37.0005, -121.9995),
            baseRadiusMeters = 10.0
        )

        invoking {
            PolygonWakeCircleValidator().prepare(geometry, wakeCircle)
        } shouldThrow IllegalArgumentException::class
    }

    @Test
    fun prepare_givenPaddedCircleBeyondSupportedLimit_expectRejected() {
        val wakeCircle = PolygonWakeCircle(
            center = point(37.0005, -121.9995),
            baseRadiusMeters = 99_500.0
        )

        invoking {
            PolygonWakeCircleValidator().prepare(geometry, wakeCircle)
        } shouldThrow IllegalArgumentException::class
    }

    private fun point(latitude: Double, longitude: Double) =
        PolygonCoordinate(latitude, longitude)

    private companion object {
        // The validator's own model, so a vertex lands where the test says it does.
        const val METRES_PER_DEGREE_LATITUDE = 111_194.93
        const val METRES_1004 = 1_004.0
        const val METRES_1050 = 1_050.0
    }
}
