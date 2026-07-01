package io.customer.geofence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Server-driven geofence configuration. Overrides the fallback values in [GeofenceConstants].
 * Persisted JSON keys are pinned via [SerialName] so they survive Kotlin renames and R8 / obfuscation.
 */
@Serializable
internal data class GeofenceConfig(
    // Radius (m) of the movement-trigger geofence; its EXIT re-ranks nearby business geofences.
    @SerialName("localRefreshTriggerRadius")
    val localRefreshTriggerRadius: Float,
    // Distance (m) from the last API anchor at which an EXIT escalates to a fresh server fetch.
    @SerialName("remoteFetchRefreshTriggerRadius")
    val remoteFetchRefreshTriggerRadius: Float,
    // Min interval (ms) between server fetches; a successful sync within this window is reused.
    @SerialName("remoteFetchRefreshExpiry")
    val remoteFetchRefreshExpiry: Long,
    // Window (ms) for suppressing duplicate transition events for the same geofence.
    @SerialName("duplicateEventsExpiry")
    val duplicateEventsExpiry: Long,
    // Max business geofences registered at once (the movement trigger is extra).
    @SerialName("maxBusinessGeofences")
    val maxBusinessGeofences: Int,
    // Cap (m) on how far a business geofence can be from the device to be registered.
    @SerialName("maxMonitoringDistance")
    val maxMonitoringDistance: Float
) {
    internal companion object {
        // Used when the cached server config is missing. Today that's the
        // common case (backend doesn't ship `config` yet); long-term it
        // covers cold-start / first-launch.
        fun fallback(): GeofenceConfig = GeofenceConfig(
            localRefreshTriggerRadius = GeofenceConstants.FALLBACK_LOCAL_REFRESH_RADIUS_METERS,
            remoteFetchRefreshTriggerRadius = GeofenceConstants.FALLBACK_REMOTE_FETCH_RADIUS_METERS,
            remoteFetchRefreshExpiry = GeofenceConstants.STALE_THRESHOLD_MS,
            duplicateEventsExpiry = GeofenceConstants.DEDUPE_COOLDOWN_MS,
            maxBusinessGeofences = GeofenceConstants.FALLBACK_MAX_BUSINESS_GEOFENCES,
            maxMonitoringDistance = GeofenceConstants.FALLBACK_MAX_MONITORING_DISTANCE_METERS
        )
    }
}
