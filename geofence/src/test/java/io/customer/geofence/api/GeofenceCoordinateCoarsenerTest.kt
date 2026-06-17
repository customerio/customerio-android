package io.customer.geofence.api

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class GeofenceCoordinateCoarsenerTest {

    @Test
    fun coarsen_givenPreciseCoordinate_expectRoundedToGrid() {
        GeofenceCoordinateCoarsener.coarsen(37.774929) shouldBeEqualTo 37.77
        GeofenceCoordinateCoarsener.coarsen(-122.419416) shouldBeEqualTo -122.42
    }

    @Test
    fun coarsen_givenTwoPointsInSameCell_expectIdenticalOutput() {
        // ~500m apart but snap to the same grid value, so repeated syncs from the area
        // can't be averaged back to the true position.
        GeofenceCoordinateCoarsener.coarsen(37.7701) shouldBeEqualTo GeofenceCoordinateCoarsener.coarsen(37.7749)
    }

    @Test
    fun coarsen_givenAlreadyCoarseValue_expectUnchanged() {
        GeofenceCoordinateCoarsener.coarsen(37.77) shouldBeEqualTo 37.77
        GeofenceCoordinateCoarsener.coarsen(0.0) shouldBeEqualTo 0.0
    }
}
