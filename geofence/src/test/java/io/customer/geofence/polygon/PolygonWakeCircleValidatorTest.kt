package io.customer.geofence.polygon

import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.Test

class PolygonWakeCircleValidatorTest {
    @Test
    fun prepare_givenARetailSizedCircle_expectItRegisteredAsSent() {
        // The base circle already encloses the ring, so padding it only moves the wake further
        // from the venue.
        val wakeCircle = PolygonWakeCircle(
            center = point(37.0005, -121.9995),
            baseRadiusMeters = 101.0
        )

        val trigger = PolygonWakeCircleValidator().prepare(wakeCircle)

        trigger.center shouldBeEqualTo wakeCircle.center
        trigger.radiusMeters shouldBeEqualTo 101f
    }

    @Test
    fun prepare_givenAKilometreWideCircle_expectItRegisteredAsSent() {
        val wakeCircle = PolygonWakeCircle(
            center = point(37.0005, -121.9995),
            baseRadiusMeters = 5_000.0
        )

        PolygonWakeCircleValidator().prepare(wakeCircle).radiusMeters shouldBeEqualTo 5_000f
    }

    @Test
    fun prepare_givenCircleBeyondSupportedLimit_expectRejected() {
        val wakeCircle = PolygonWakeCircle(
            center = point(37.0005, -121.9995),
            baseRadiusMeters = PolygonWakeCircleValidator.MAXIMUM_TRIGGER_RADIUS_METERS + 1.0
        )

        invoking {
            PolygonWakeCircleValidator().prepare(wakeCircle)
        } shouldThrow IllegalArgumentException::class
    }

    @Test
    fun prepare_givenACircleSmallerThanAnyBackendSends_expectNoFloorApplied() {
        // No floor: the circle is registered exactly as sent, however small.
        val wakeCircle = PolygonWakeCircle(
            center = point(37.0005, -121.9995),
            baseRadiusMeters = 30.0
        )

        PolygonWakeCircleValidator().prepare(wakeCircle).radiusMeters shouldBeEqualTo 30f
    }

    private fun point(latitude: Double, longitude: Double) =
        PolygonCoordinate(latitude, longitude)
}
