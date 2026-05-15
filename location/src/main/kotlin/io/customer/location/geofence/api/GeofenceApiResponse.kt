package io.customer.location.geofence.api

import io.customer.location.geofence.GeofenceConfig
import io.customer.location.geofence.GeofenceRegion
import io.customer.location.geofence.GeofenceTransitionType
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class GeofenceApiResponse(
    val config: GeofenceApiConfig,
    val geofences: List<GeofenceApiRegion>
)

@Serializable
internal data class GeofenceApiConfig(
    @SerialName("movement_trigger_radius")
    val movementTriggerRadius: Float,
    @SerialName("local_refresh_trigger_radius")
    val localRefreshTriggerRadius: Float,
    @SerialName("remote_fetch_refresh_trigger_radius")
    val remoteFetchRefreshTriggerRadius: Float,
    @SerialName("remote_fetch_refresh_expiry_time")
    val remoteFetchRefreshExpiryTime: Long,
    @SerialName("duplicate_events_expiry_time")
    val duplicateEventsExpiryTime: Long,
    val android: GeofenceApiPlatformConfig
)

@Serializable
internal data class GeofenceApiPlatformConfig(
    @SerialName("max_business_geofence")
    val maxBusinessGeofence: Int
)

@Serializable
internal data class GeofenceApiRegion(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Float,
    @SerialName("transition_types")
    val transitionTypes: List<String>,
    @SerialName("last_updated")
    val lastUpdated: Long
)

internal fun GeofenceApiResponse.toDomainConfig(): GeofenceConfig = config.toDomain()

internal fun GeofenceApiResponse.toDomainRegions(): List<GeofenceRegion> =
    geofences.mapNotNull { it.toDomain() }

private fun GeofenceApiConfig.toDomain(): GeofenceConfig = GeofenceConfig(
    movementTriggerRadius = movementTriggerRadius,
    localRefreshTriggerRadius = localRefreshTriggerRadius,
    remoteFetchRefreshTriggerRadius = remoteFetchRefreshTriggerRadius,
    remoteFetchRefreshExpiryMs = TimeUnit.SECONDS.toMillis(remoteFetchRefreshExpiryTime),
    duplicateEventsExpiryMs = TimeUnit.SECONDS.toMillis(duplicateEventsExpiryTime),
    maxBusinessGeofences = android.maxBusinessGeofence
)

private fun GeofenceApiRegion.toDomain(): GeofenceRegion? {
    val types = transitionTypes.mapNotNull { parseTransitionType(it) }
    if (types.isEmpty()) return null
    return GeofenceRegion(
        id = id,
        name = name,
        latitude = latitude,
        longitude = longitude,
        radius = radius,
        transitionTypes = types,
        lastUpdated = lastUpdated
    )
}

private fun parseTransitionType(value: String): GeofenceTransitionType? =
    when (value.lowercase()) {
        "enter" -> GeofenceTransitionType.ENTER
        "exit" -> GeofenceTransitionType.EXIT
        else -> null
    }
