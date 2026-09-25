package io.customer.geofence.polygon

import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.Test

class PolygonWakeCircleValidatorTest {
    @Test
    fun prepare_givenARetailSizedCircle_expectItRegisteredAsSent() {
        // A real workspace polygon catalogues a 101 m base circle. It already encloses the ring,
        // so anything added to it only moves the wake further from the venue: the 400 m floor this
        // replaces woke us 193 m outside that shop on 2026-09-18 and the visit went unrecorded.
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
        // The same rule at the other end of the catalogue, where the old floor was inert.
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
        // Nothing clamps a small circle up. Whether GMS triggers one this small is unknown below
        // 250 m in this repository and is what the next field capture is for; inventing a floor
        // here would only hide which radius was registered when that capture is read.
        val wakeCircle = PolygonWakeCircle(
            center = point(37.0005, -121.9995),
            baseRadiusMeters = 30.0
        )

        PolygonWakeCircleValidator().prepare(wakeCircle).radiusMeters shouldBeEqualTo 30f
    }

    private fun point(latitude: Double, longitude: Double) =
        PolygonCoordinate(latitude, longitude)
}
