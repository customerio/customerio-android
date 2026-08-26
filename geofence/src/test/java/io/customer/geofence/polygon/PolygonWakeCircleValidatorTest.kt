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
}
