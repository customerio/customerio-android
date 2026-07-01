package io.customer.geofence.api

import io.customer.geofence.GeofenceConfig
import io.customer.geofence.GeofenceConstants
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.GeofenceTransitionType
import io.customer.geofence.di.geofenceLogger
import io.customer.sdk.core.di.SDKComponent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape of `GET /v1/geofences/nearby`. Backend ships only `geofences`
 * today; `config` and per-region `transition_types` / `last_updated` are
 * nullable forward-compat slots the SDK will honor when backend adds them.
 */
@Serializable
internal data class GeofenceApiResponse(
    @SerialName("config")
    val config: GeofenceApiConfig? = null,
    @SerialName("geofences")
    val geofences: List<GeofenceApiRegion>
)

// Every field nullable so backend can roll fields out gradually; per-field
// fallbacks live in [toDomain].
@Serializable
internal data class GeofenceApiConfig(
    @SerialName("local_refresh_trigger_radius")
    val localRefreshTriggerRadius: Float? = null,
    @SerialName("remote_fetch_refresh_trigger_radius")
    val remoteFetchRefreshTriggerRadius: Float? = null,
    @SerialName("remote_fetch_refresh_expiry_time")
    val remoteFetchRefreshExpiryTime: Long? = null,
    @SerialName("duplicate_events_expiry_time")
    val duplicateEventsExpiryTime: Long? = null,
    @SerialName("max_monitoring_distance")
    val maxMonitoringDistance: Float? = null,
    @SerialName("android")
    val android: GeofenceApiPlatformConfig? = null
)

@Serializable
internal data class GeofenceApiPlatformConfig(
    @SerialName("max_business_geofence")
    val maxBusinessGeofence: Int? = null
)

@Serializable
internal data class GeofenceApiRegion(
    // Used as the OS request ID and the `geofenceId` key on transition events.
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String = "",
    @SerialName("latitude")
    val latitude: Double,
    @SerialName("longitude")
    val longitude: Double,
    @SerialName("radius")
    val radius: Int,
    @SerialName("external_id")
    val externalId: String? = null,
    @SerialName("transition_types")
    val transitionTypes: List<String>? = null,
    @SerialName("last_updated")
    val lastUpdated: Long? = null
)

/** Returns `null` when backend didn't send a `config` block — gates the cache save. */
internal fun GeofenceApiResponse.toDomainConfig(): GeofenceConfig? =
    config?.toDomain()

internal fun GeofenceApiResponse.toDomainRegions(): List<GeofenceRegion> =
    geofences.map { it.toDomain() }

// Coerces raw server values into sane bounds so a misconfigured backend can't push the SDK into a
// pathological state: non-positive values fall back; positive out-of-range values clamp.
private fun GeofenceApiConfig.toDomain(): GeofenceConfig {
    val coercedLocalRefresh = localRefreshTriggerRadius?.takeIf { it > 0 }
        ?.coerceIn(
            GeofenceConstants.MIN_LOCAL_REFRESH_RADIUS_METERS,
            GeofenceConstants.MAX_LOCAL_REFRESH_RADIUS_METERS
        )
        ?: GeofenceConstants.FALLBACK_LOCAL_REFRESH_RADIUS_METERS
    // null → default cap; 0 → explicitly disabled (no cap). A positive value below the trigger
    // radius would create a dead-zone (a geofence inside the trigger but beyond the cap never gets
    // re-ranked), so fall back to the default.
    val coercedMaxMonitoringDistance = when {
        maxMonitoringDistance == null -> GeofenceConstants.FALLBACK_MAX_MONITORING_DISTANCE_METERS
        maxMonitoringDistance == 0f -> GeofenceConstants.NO_MONITORING_DISTANCE_CAP_METERS
        maxMonitoringDistance < coercedLocalRefresh -> GeofenceConstants.FALLBACK_MAX_MONITORING_DISTANCE_METERS
        else -> maxMonitoringDistance
    }
    return GeofenceConfig(
        localRefreshTriggerRadius = coercedLocalRefresh,
        remoteFetchRefreshTriggerRadius = remoteFetchRefreshTriggerRadius?.takeIf { it > 0 }
            ?: GeofenceConstants.FALLBACK_REMOTE_FETCH_RADIUS_METERS,
        remoteFetchRefreshExpiry = remoteFetchRefreshExpiryTime?.takeIf { it > 0 }
            ?.coerceIn(
                GeofenceConstants.MIN_REMOTE_FETCH_REFRESH_EXPIRY_MS,
                GeofenceConstants.MAX_REMOTE_FETCH_REFRESH_EXPIRY_MS
            )
            ?: GeofenceConstants.STALE_THRESHOLD_MS,
        duplicateEventsExpiry = duplicateEventsExpiryTime?.takeIf { it > 0 }
            ?.coerceIn(
                GeofenceConstants.MIN_DUPLICATE_EVENTS_EXPIRY_MS,
                GeofenceConstants.MAX_DUPLICATE_EVENTS_EXPIRY_MS
            )
            ?: GeofenceConstants.DEDUPE_COOLDOWN_MS,
        // Range is 0..99: zero is a valid server-side kill switch; 99 leaves one
        // OS slot for the movement trigger. Out-of-range values fall back.
        maxBusinessGeofences = android?.maxBusinessGeofence?.takeIf { it in 0..99 }
            ?: GeofenceConstants.FALLBACK_MAX_BUSINESS_GEOFENCES,
        maxMonitoringDistance = coercedMaxMonitoringDistance
    )
}

private fun GeofenceApiRegion.toDomain(): GeofenceRegion = GeofenceRegion(
    id = id,
    name = name,
    externalId = externalId,
    latitude = latitude,
    longitude = longitude,
    radius = radius.toFloat(),
    transitionTypes = resolveTransitionTypes(transitionTypes),
    lastUpdated = lastUpdated ?: 0L
)

/**
 * Null / empty / all-unknown values fall back to `[ENTER, EXIT]`; mixed
 * valid + unknown keeps just the valid subset. Each unknown value is logged.
 */
private fun resolveTransitionTypes(raw: List<String>?): List<GeofenceTransitionType> {
    val defaults = listOf(GeofenceTransitionType.ENTER, GeofenceTransitionType.EXIT)
    if (raw.isNullOrEmpty()) return defaults
    val parsed = raw.mapNotNull { value ->
        parseTransitionType(value) ?: run {
            SDKComponent.geofenceLogger.logUnknownApiTransitionType(value)
            null
        }
    }
    return parsed.takeIf { it.isNotEmpty() } ?: defaults
}

private fun parseTransitionType(value: String): GeofenceTransitionType? =
    when (value.lowercase()) {
        "enter" -> GeofenceTransitionType.ENTER
        "exit" -> GeofenceTransitionType.EXIT
        else -> null
    }
