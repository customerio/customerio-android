package io.customer.location.geofence.api

import io.customer.location.geofence.GeofenceConfig
import io.customer.location.geofence.GeofenceConstants
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

// All config fields are nullable with defaults so the SDK keeps working if the
// backend rolls out fields gradually or temporarily returns invalid values.
// Per-field fallbacks are applied in [toDomain].
@Serializable
internal data class GeofenceApiConfig(
    @SerialName("movement_trigger_radius")
    val movementTriggerRadius: Float? = null,
    @SerialName("local_refresh_trigger_radius")
    val localRefreshTriggerRadius: Float? = null,
    @SerialName("remote_fetch_refresh_trigger_radius")
    val remoteFetchRefreshTriggerRadius: Float? = null,
    @SerialName("remote_fetch_refresh_expiry_time")
    val remoteFetchRefreshExpiryTime: Long? = null,
    @SerialName("duplicate_events_expiry_time")
    val duplicateEventsExpiryTime: Long? = null,
    val android: GeofenceApiPlatformConfig? = null
)

@Serializable
internal data class GeofenceApiPlatformConfig(
    @SerialName("max_business_geofence")
    val maxBusinessGeofence: Int? = null
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
    movementTriggerRadius = movementTriggerRadius?.takeIf { it > 0 }
        ?: GeofenceConstants.FALLBACK_MOVEMENT_TRIGGER_RADIUS_METERS,
    localRefreshTriggerRadius = localRefreshTriggerRadius?.takeIf { it > 0 }
        ?: GeofenceConstants.FALLBACK_LOCAL_REFRESH_RADIUS_METERS,
    remoteFetchRefreshTriggerRadius = remoteFetchRefreshTriggerRadius?.takeIf { it > 0 }
        ?: GeofenceConstants.FALLBACK_REMOTE_FETCH_RADIUS_METERS,
    remoteFetchRefreshExpiryMs = remoteFetchRefreshExpiryTime?.takeIf { it > 0 }
        ?.let { TimeUnit.SECONDS.toMillis(it) }
        ?: GeofenceConstants.STALE_THRESHOLD_MS,
    duplicateEventsExpiryMs = duplicateEventsExpiryTime?.takeIf { it > 0 }
        ?.let { TimeUnit.SECONDS.toMillis(it) }
        ?: GeofenceConstants.DEDUPE_COOLDOWN_MS,
    // Cap at OS per-app geofence limit (100 total slots, minus 1 for the
    // movement trigger = 99 for business). Zero is a valid value (server-side
    // kill switch — no business geofences and no movement trigger).
    maxBusinessGeofences = android?.maxBusinessGeofence?.takeIf { it in 0..99 }
        ?: GeofenceConstants.FALLBACK_MAX_BUSINESS_GEOFENCES
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
