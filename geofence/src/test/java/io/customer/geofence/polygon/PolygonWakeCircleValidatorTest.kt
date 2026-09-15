package io.customer.geofence.polygon

import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.Test

class PolygonWakeCircleValidatorTest {
    @Test
    fun prepare_givenARetailSizedCircle_expectTheFloorWithoutRecentering() {
        // A real workspace polygon catalogues a 101 m base circle. GMS coarse containment is wrong
        // by hundreds of metres, so a ring this small would be driven by that error rather than by
        // the device's position.
        val wakeCircle = PolygonWakeCircle(
            center = point(37.0005, -121.9995),
            baseRadiusMeters = 101.0
        )

        val trigger = PolygonWakeCircleValidator().prepare(wakeCircle)

        trigger.center shouldBeEqualTo wakeCircle.center
        trigger.radiusMeters shouldBeEqualTo
            PolygonWakeCircleValidator.MINIMUM_TRIGGER_RADIUS_METERS.toFloat()
    }

    @Test
    fun prepare_givenCircleAlreadyAboveTheFloor_expectItRegisteredVerbatim() {
        // The floor never pads. Adding a kilometre to a circle that is already kilometres wide is
        // what the old rule did, and it bought nothing.
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

    private fun point(latitude: Double, longitude: Double) =
        PolygonCoordinate(latitude, longitude)
}
