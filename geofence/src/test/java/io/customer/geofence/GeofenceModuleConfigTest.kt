package io.customer.geofence

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class GeofenceModuleConfigTest {

    @Test
    fun build_givenNoOverrides_expectAutomaticDefault() {
        val config = GeofenceModuleConfig.Builder().build()

        config.locationMode shouldBeEqualTo GeofenceLocationMode.AUTOMATIC
        config.polygonTrackingMode shouldBeEqualTo PolygonTrackingMode.RESPONSIVE
    }

    @Test
    fun build_givenManualLocationMode_expectManual() {
        val config = GeofenceModuleConfig.Builder()
            .setLocationMode(GeofenceLocationMode.MANUAL)
            .build()

        config.locationMode shouldBeEqualTo GeofenceLocationMode.MANUAL
    }

    @Test
    fun build_givenContinuousPolygonTracking_expectContinuous() {
        val config = GeofenceModuleConfig.Builder()
            .setPolygonTrackingMode(PolygonTrackingMode.CONTINUOUS)
            .build()

        config.polygonTrackingMode shouldBeEqualTo PolygonTrackingMode.CONTINUOUS
    }
}
