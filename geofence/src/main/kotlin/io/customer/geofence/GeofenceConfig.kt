package io.customer.geofence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Server-driven geofence configuration. Overrides the fallback values in [GeofenceConstants].
 * Persisted JSON keys are pinned via [SerialName] so they survive Kotlin renames and R8 / obfuscation.
 */
@Serializable
internal data class GeofenceConfig(
    @SerialName("localRefreshTriggerRadius")
    val localRefreshTriggerRadius: Float,
    @SerialName("remoteFetchRefreshTriggerRadius")
    val remoteFetchRefreshTriggerRadius: Float,
    @SerialName("remoteFetchRefreshExpiry")
    val remoteFetchRefreshExpiry: Long,
    @SerialName("duplicateEventsExpiry")
    val duplicateEventsExpiry: Long,
    @SerialName("maxBusinessGeofences")
    val maxBusinessGeofences: Int
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
            maxBusinessGeofences = GeofenceConstants.FALLBACK_MAX_BUSINESS_GEOFENCES
        )
    }
}
