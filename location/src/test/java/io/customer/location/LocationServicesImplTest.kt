package io.customer.location

import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test

class LocationServicesImplTest {

    @Test
    fun isValidCoordinate_givenValidCoordinates_expectTrue() {
        LocationServicesImpl.isValidCoordinate(37.7749, -122.4194).shouldBeTrue()
    }

    @Test
    fun isValidCoordinate_givenBoundaryValues_expectTrue() {
        LocationServicesImpl.isValidCoordinate(90.0, 180.0).shouldBeTrue()
        LocationServicesImpl.isValidCoordinate(-90.0, -180.0).shouldBeTrue()
        LocationServicesImpl.isValidCoordinate(0.0, 0.0).shouldBeTrue()
    }

    @Test
    fun isValidCoordinate_givenLatitudeOutOfRange_expectFalse() {
        LocationServicesImpl.isValidCoordinate(91.0, 0.0).shouldBeFalse()
        LocationServicesImpl.isValidCoordinate(-91.0, 0.0).shouldBeFalse()
    }

    @Test
    fun isValidCoordinate_givenLongitudeOutOfRange_expectFalse() {
        LocationServicesImpl.isValidCoordinate(0.0, 181.0).shouldBeFalse()
        LocationServicesImpl.isValidCoordinate(0.0, -181.0).shouldBeFalse()
    }

    @Test
    fun isValidCoordinate_givenNaN_expectFalse() {
        LocationServicesImpl.isValidCoordinate(Double.NaN, 0.0).shouldBeFalse()
        LocationServicesImpl.isValidCoordinate(0.0, Double.NaN).shouldBeFalse()
    }

    @Test
    fun isValidCoordinate_givenInfinity_expectFalse() {
        LocationServicesImpl.isValidCoordinate(Double.POSITIVE_INFINITY, 0.0).shouldBeFalse()
        LocationServicesImpl.isValidCoordinate(0.0, Double.NEGATIVE_INFINITY).shouldBeFalse()
    }
}
