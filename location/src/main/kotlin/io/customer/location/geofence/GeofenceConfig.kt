package io.customer.location.geofence

/**
 * Server-driven geofence configuration. Overrides the fallback values in [GeofenceConstants].
 * Times are normalized to milliseconds at the API boundary.
 */
internal data class GeofenceConfig(
    val movementTriggerRadius: Float,
    val localRefreshTriggerRadius: Float,
    val remoteFetchRefreshTriggerRadius: Float,
    val remoteFetchRefreshExpiryMs: Long,
    val duplicateEventsExpiryMs: Long,
    val maxBusinessGeofences: Int
)
