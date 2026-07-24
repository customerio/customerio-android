package io.customer.geofence

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class GeofenceModuleConfigTest {

    @Test
    fun build_givenNoOverrides_expectAutomaticDefault() {
        val config = GeofenceModuleConfig.Builder().build()

        config.locationMode shouldBeEqualTo GeofenceLocationMode.AUTOMATIC
    }

    @Test
    fun build_givenManualLocationMode_expectManual() {
        val config = GeofenceModuleConfig.Builder()
            .setLocationMode(GeofenceLocationMode.MANUAL)
            .build()

        config.locationMode shouldBeEqualTo GeofenceLocationMode.MANUAL
    }

    @Test
    fun build_givenOffLocationMode_expectOff() {
        val config = GeofenceModuleConfig.Builder()
            .setLocationMode(GeofenceLocationMode.OFF)
            .build()

        config.locationMode shouldBeEqualTo GeofenceLocationMode.OFF
    }

    @Test
    fun isEnabled_givenActiveModes_expectTrue() {
        GeofenceModuleConfig.Builder().setLocationMode(GeofenceLocationMode.AUTOMATIC).build()
            .isEnabled shouldBeEqualTo true
        GeofenceModuleConfig.Builder().setLocationMode(GeofenceLocationMode.MANUAL).build()
            .isEnabled shouldBeEqualTo true
    }

    @Test
    fun isEnabled_givenOff_expectFalse() {
        GeofenceModuleConfig.Builder().setLocationMode(GeofenceLocationMode.OFF).build()
            .isEnabled shouldBeEqualTo false
    }
}
