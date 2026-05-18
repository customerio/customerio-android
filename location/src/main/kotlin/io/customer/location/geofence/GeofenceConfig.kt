package io.customer.location.geofence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Server-driven geofence configuration. Overrides the fallback values in [GeofenceConstants].
 * Times are normalized to milliseconds at the API boundary. Persisted JSON keys are pinned
 * via [SerialName] so they survive Kotlin renames and R8 / obfuscation.
 */
@Serializable
internal data class GeofenceConfig(
    @SerialName("localRefreshTriggerRadius") val localRefreshTriggerRadius: Float,
    @SerialName("remoteFetchRefreshTriggerRadius") val remoteFetchRefreshTriggerRadius: Float,
    @SerialName("remoteFetchRefreshExpiryMs") val remoteFetchRefreshExpiryMs: Long,
    @SerialName("duplicateEventsExpiryMs") val duplicateEventsExpiryMs: Long,
    @SerialName("maxBusinessGeofences") val maxBusinessGeofences: Int
)
