package io.customer.geofence

import androidx.annotation.VisibleForTesting

/**
 * TESTING ONLY (geofence-testing branch) — delete with the branch; must never reach feature/main.
 *
 * Forces a few client-side config values so on-device behavior can be validated independently of
 * what the server ships, ahead of proposing the same values server-side. Applied to every
 * [GeofenceConfig] the SDK resolves (server-parsed, cached, and fallback).
 *
 * Enabled by default so the running sample app picks it up with no init hook. Unit tests set
 * [enabled] to false so they keep asserting real production config resolution.
 */
internal object GeofenceTestConfigOverrides {
    @VisibleForTesting
    var enabled: Boolean = true

    fun apply(config: GeofenceConfig): GeofenceConfig =
        if (!enabled) {
            config
        } else {
            config.copy(
                localRefreshTriggerRadius = 5_000f,
                remoteFetchRefreshTriggerRadius = 20_000f,
                maxMonitoringDistance = 50_000f,
                // Testing only: 1h freshness window to make staleness / re-fetch easy to trigger.
                remoteFetchRefreshExpiry = 60 * 60 * 1_000L
            )
        }
}
