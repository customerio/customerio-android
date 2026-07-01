package io.customer.geofence

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeLessThan
import org.junit.Test

class GeofenceLocationTest {

    @Test
    fun coarsened_given500mGrid_expectSnappedAndTrimmedToGrid() {
        val original = GeofenceLocation(latitude = 37.7749295, longitude = -122.4194155)

        val coarse = original.coarsened(500.0)

        // Snapped to the uniform ~500 m grid and trimmed of binary-float noise (clean 6 dp).
        coarse.latitude shouldBeEqualTo 37.773985
        coarse.longitude shouldBeEqualTo -122.417457
    }

    @Test
    fun coarsened_givenHighLatitude_expectLongitudeGridWidensToStayUniform() {
        // The point of the grid over decimal rounding: at 60° (cos ≈ 0.5) a degree of longitude is
        // ~half the ground distance, so the longitude grid roughly doubles in degrees to keep the
        // ~500 m floor. Decimal rounding would instead over-refine longitude here.
        val original = GeofenceLocation(latitude = 60.0, longitude = 10.123456)

        val coarse = original.coarsened(500.0)

        Math.abs(coarse.longitude - original.longitude) shouldBeLessThan 0.0090 // ~half of the ~0.009° cell
    }
}
