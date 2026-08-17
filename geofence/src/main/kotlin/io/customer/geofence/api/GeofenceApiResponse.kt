package io.customer.geofence.api

import io.customer.geofence.GeofenceConfig
import io.customer.geofence.GeofenceConstants
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.GeofenceTransitionType
import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.polygon.PolygonCoordinate
import io.customer.geofence.polygon.PolygonEnclosingCircle
import io.customer.geofence.polygon.PolygonGeometry
import io.customer.location.LocationCoordinates
import io.customer.sdk.core.di.SDKComponent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Wire shape of `POST /geofences/nearest`. `config` and the per-region
 * `name` / `transition_types` / `last_updated` fields are optional forward-compat
 * slots the SDK honors if the backend sends them and silently skips otherwise.
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
    val name: String? = null,
    @SerialName("latitude")
    val latitude: Double? = null,
    @SerialName("longitude")
    val longitude: Double? = null,
    // Double (not Int) so a fractional radius can't fail the whole response decode.
    @SerialName("radius")
    val radius: Double? = null,
    @SerialName("geometry")
    val geometry: GeofenceApiGeometry? = null,
    @SerialName("external_id")
    val externalId: String? = null,
    @SerialName("transition_types")
    val transitionTypes: List<String>? = null,
    @SerialName("last_updated")
    val lastUpdated: Long? = null,
    @SerialName("geoset_ids")
    val geosetIds: List<String> = emptyList(),
    // Decoded as a tolerant JsonElement (not a typed map) so a malformed `metadata` — a non-object,
    // or bad values inside — can never fail the region/response decode; [sanitizeMetadata] reduces
    // anything that isn't a scalar object to empty.
    @SerialName("metadata")
    val metadata: JsonElement? = null
)

@Serializable
internal data class GeofenceApiGeometry(
    @SerialName("type")
    val type: String,
    @SerialName("coordinates")
    val coordinates: JsonElement
)

/** Returns `null` when backend didn't send a `config` block — gates the cache save. */
internal fun GeofenceApiResponse.toDomainConfig(): GeofenceConfig? =
    config?.toDomain()

// One bad region costs itself (dropped + logged). A non-empty response whose regions ALL drop
// is unusable — throw so the caller fails the refresh instead of clearing live state.
internal fun GeofenceApiResponse.toDomainRegions(): List<GeofenceRegion> {
    if (geofences.isEmpty()) return emptyList()

    val mapped = geofences.mapNotNull { region ->
        try {
            region.toDomain() ?: run {
                SDKComponent.geofenceLogger.logInvalidRegionDropped(region.id)
                null
            }
        } catch (e: Exception) {
            SDKComponent.geofenceLogger.logRegionMappingFailed(region.id, e.message)
            null
        }
    }

    if (mapped.isEmpty()) error("all ${geofences.size} regions dropped")
    return mapped
}

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

// Null when the region violates Geofence.Builder preconditions; one bad region must not cost the whole sync.
internal fun GeofenceApiRegion.toDomain(): GeofenceRegion? {
    if (id.isBlank()) return null
    val polygonVertices = geometry?.takeIf { it.type.equals("Polygon", ignoreCase = true) }
        ?.coordinates
        ?.toPolygonVertices()
    if (geometry?.type.equals("Polygon", ignoreCase = true) && polygonVertices == null) return null

    val polygon = polygonVertices?.let(PolygonGeometry::from)
    val trigger = polygon?.let(PolygonEnclosingCircle()::calculate)
    val resolvedLatitude = trigger?.center?.latitude ?: latitude
    val resolvedLongitude = trigger?.center?.longitude ?: longitude
    val resolvedRadius = trigger?.radiusMeters?.toDouble() ?: radius
    if (
        resolvedRadius == null || resolvedRadius <= 0 ||
        resolvedLatitude == null || resolvedLongitude == null ||
        !LocationCoordinates.isValid(resolvedLatitude, resolvedLongitude)
    ) {
        return null
    }
    return GeofenceRegion(
        id = id,
        name = name,
        externalId = externalId,
        latitude = resolvedLatitude,
        longitude = resolvedLongitude,
        radius = resolvedRadius.toFloat(),
        transitionTypes = resolveTransitionTypes(transitionTypes),
        lastUpdated = lastUpdated ?: 0L,
        geosetIds = geosetIds,
        metadata = sanitizeMetadata(metadata),
        polygonVertices = polygon?.vertices
    )
}

private fun JsonElement.toPolygonVertices(): List<PolygonCoordinate>? {
    val rings = this as? JsonArray ?: return null
    if (rings.size != 1) return null
    val outerRing = rings.singleOrNull() as? JsonArray ?: return null
    return outerRing.map { rawPosition ->
        val position = rawPosition as? JsonArray ?: return null
        if (position.size < 2) return null
        val longitude = (position[0] as? JsonPrimitive)?.doubleOrNull ?: return null
        val latitude = (position[1] as? JsonPrimitive)?.doubleOrNull ?: return null
        runCatching { PolygonCoordinate(latitude, longitude) }.getOrNull() ?: return null
    }
}

/**
 * Reduces the raw wire value to the scalar map the event can carry: anything that isn't a JSON object
 * (or is absent) becomes empty, non-scalar/null values are dropped, and count/size are capped as a
 * backstop (see [GeofenceConstants]). Key order makes the capping deterministic. Never throws, so a
 * malformed `metadata` yields empty metadata rather than failing the region.
 */
private fun sanitizeMetadata(raw: JsonElement?): Map<String, JsonElement> {
    val obj = raw as? JsonObject ?: return emptyMap()
    if (obj.isEmpty()) return emptyMap()
    val kept = LinkedHashMap<String, JsonElement>()
    var totalBytes = 0L
    for (key in obj.keys.sorted()) {
        if (kept.size >= GeofenceConstants.MAX_METADATA_COUNT) break
        val primitive = obj.getValue(key) as? JsonPrimitive ?: continue
        if (primitive is JsonNull) continue
        totalBytes += key.toByteArray().size + primitive.content.toByteArray().size
        if (totalBytes > GeofenceConstants.MAX_METADATA_PAYLOAD_BYTES) break
        kept[key] = primitive
    }
    return kept
}

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
