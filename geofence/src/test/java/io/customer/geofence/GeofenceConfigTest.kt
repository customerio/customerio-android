package io.customer.geofence

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class GeofenceConfigTest {

    private fun config(
        maxMonitoringDistance: Float,
        remoteFetchRefreshTriggerRadius: Float = 5_000f
    ): GeofenceConfig = GeofenceConfig(
        localRefreshTriggerRadius = 1_000f,
        remoteFetchRefreshTriggerRadius = remoteFetchRefreshTriggerRadius,
        remoteFetchRefreshExpiry = 86_400_000L,
        duplicateEventsExpiry = 3_600_000L,
        maxBusinessGeofences = 19,
        maxMonitoringDistance = maxMonitoringDistance
    )

    @Test
    fun remoteSearchRadiusMeters_givenMonitoringCapLargerThanTrigger_expectCap() {
        config(maxMonitoringDistance = 50_000f).remoteSearchRadiusMeters() shouldBeEqualTo 50_000.0
    }

    @Test
    fun remoteSearchRadiusMeters_givenTriggerLargerThanMonitoringCap_expectTrigger() {
        // A tight monitoring cap must not shrink the search below the re-fetch radius, or a fence the
        // device approaches before the next re-fetch would be absent from the set.
        config(maxMonitoringDistance = 2_000f, remoteFetchRefreshTriggerRadius = 5_000f)
            .remoteSearchRadiusMeters() shouldBeEqualTo 5_000.0
    }

    @Test
    fun remoteSearchRadiusMeters_givenDisabledCap_expectFiniteFallbackNotFloatMax() {
        // Disabled cap (Float.MAX_VALUE) must collapse to the finite fallback so the wire value stays sane.
        config(maxMonitoringDistance = GeofenceConstants.NO_MONITORING_DISTANCE_CAP_METERS)
            .remoteSearchRadiusMeters() shouldBeEqualTo
            GeofenceConstants.FALLBACK_MAX_MONITORING_DISTANCE_METERS.toDouble()
    }
}
