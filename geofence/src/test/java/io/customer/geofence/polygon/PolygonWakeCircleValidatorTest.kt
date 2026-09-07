package io.customer.geofence.polygon

import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.Test

class PolygonWakeCircleValidatorTest {
    @Test
    fun prepare_givenBackendCircleContainingPolygon_expectPlatformMarginAppliedWithoutRecentering() {
        val wakeCircle = PolygonWakeCircle(
            center = point(37.0005, -121.9995),
            baseRadiusMeters = 100.0
        )

        val trigger = PolygonWakeCircleValidator().prepare(wakeCircle)

        trigger.center shouldBeEqualTo wakeCircle.center
        trigger.radiusMeters shouldBeEqualTo 1_100f
    }

    @Test
    fun prepare_givenBackendCircleSmallerThanTheRing_expectAcceptedAtTheStatedRadius() {
        val wakeCircle = PolygonWakeCircle(
            center = point(37.0005, -121.9995),
            baseRadiusMeters = 10.0
        )

        val trigger = PolygonWakeCircleValidator().prepare(wakeCircle)

        trigger.radiusMeters shouldBeEqualTo
            (10.0 + PolygonWakeCircleValidator.PLATFORM_WAKE_MARGIN_METERS).toFloat()
    }

    @Test
    fun prepare_givenPaddedCircleBeyondSupportedLimit_expectRejected() {
        val wakeCircle = PolygonWakeCircle(
            center = point(37.0005, -121.9995),
            baseRadiusMeters = 99_500.0
        )

        invoking {
            PolygonWakeCircleValidator().prepare(wakeCircle)
        } shouldThrow IllegalArgumentException::class
    }

    private fun point(latitude: Double, longitude: Double) =
        PolygonCoordinate(latitude, longitude)
}
