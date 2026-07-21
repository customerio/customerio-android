package io.customer.location

import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test

class LocationCoordinatesTest {

    @Test
    fun isValid_givenValidCoordinates_expectTrue() {
        LocationCoordinates.isValid(37.7749, -122.4194).shouldBeTrue()
    }

    @Test
    fun isValid_givenBoundaryValues_expectTrue() {
        LocationCoordinates.isValid(90.0, 180.0).shouldBeTrue()
        LocationCoordinates.isValid(-90.0, -180.0).shouldBeTrue()
        LocationCoordinates.isValid(0.0, 0.0).shouldBeTrue()
    }

    @Test
    fun isValid_givenLatitudeOutOfRange_expectFalse() {
        LocationCoordinates.isValid(91.0, 0.0).shouldBeFalse()
        LocationCoordinates.isValid(-91.0, 0.0).shouldBeFalse()
    }

    @Test
    fun isValid_givenLongitudeOutOfRange_expectFalse() {
        LocationCoordinates.isValid(0.0, 181.0).shouldBeFalse()
        LocationCoordinates.isValid(0.0, -181.0).shouldBeFalse()
    }

    @Test
    fun isValid_givenNaN_expectFalse() {
        LocationCoordinates.isValid(Double.NaN, 0.0).shouldBeFalse()
        LocationCoordinates.isValid(0.0, Double.NaN).shouldBeFalse()
    }

    @Test
    fun isValid_givenInfinity_expectFalse() {
        LocationCoordinates.isValid(Double.POSITIVE_INFINITY, 0.0).shouldBeFalse()
        LocationCoordinates.isValid(0.0, Double.NEGATIVE_INFINITY).shouldBeFalse()
    }
}
