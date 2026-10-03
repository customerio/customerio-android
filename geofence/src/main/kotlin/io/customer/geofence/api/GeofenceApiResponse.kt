package io.customer.geofence.api

import io.customer.geofence.GeofenceCatalogEntry
import io.customer.geofence.GeofenceConfig
import io.customer.geofence.GeofenceConstants
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.GeofenceTransitionType
import io.customer.geofence.PolygonDropReason
import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.polygon.PolygonCoordinate
import io.customer.geofence.polygon.PolygonGeometry
import io.customer.geofence.polygon.PolygonSupport
import io.customer.geofence.polygon.PolygonWakeCircle
import io.customer.geofence.polygon.PolygonWakeCircleValidator
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

/** Wire shape of `POST /geofences/nearest`. */
@Serializable
internal data class GeofenceApiResponse(
    @SerialName("config")
    val config: GeofenceApiConfig? = null,
    @SerialName("geofences")
    val geofences: List<GeofenceApiRegion>
)

// Nullable so the backend can roll fields out gradually; fallbacks live in [toDomain].
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
    @SerialName("shape")
    val shape: String? = null,
    @SerialName("geometry")
    val geometry: GeofenceApiGeometry? = null,
    @SerialName("enclosing_circle")
    val enclosingCircle: GeofenceApiEnclosingCircle? = null,
    @SerialName("external_id")
    val externalId: String? = null,
    @SerialName("transition_types")
    val transitionTypes: List<String>? = null,
    @SerialName("last_updated")
    val lastUpdated: Long? = null,
    @SerialName("geoset_ids")
    val geosetIds: List<String> = emptyList(),
    // A JsonElement, not a typed map, so malformed `metadata` can't fail the response decode.
    @SerialName("metadata")
    val metadata: JsonElement? = null,
    @SerialName("dwell_threshold_seconds")
    val dwellThresholdSeconds: Long? = null
)

// Nullable throughout: these decode before [toDomainRegions]' per-record try/catch, so a required
// field would let one partial polygon reject the whole response. Absent fields drop only their record.
@Serializable
internal data class GeofenceApiGeometry(
    @SerialName("type")
    val type: String? = null,
    @SerialName("coordinates")
    val coordinates: JsonElement? = null
)

@Serializable
internal data class GeofenceApiEnclosingCircle(
    @SerialName("latitude")
    val latitude: Double? = null,
    @SerialName("longitude")
    val longitude: Double? = null,
    @SerialName("base_radius_m")
    val baseRadiusMeters: Double? = null
)

/** Null when the backend sent no `config` block, so the cached config is kept. */
internal fun GeofenceApiResponse.toDomainConfig(): GeofenceConfig? =
    config?.toDomain()

// A bad region is dropped and logged. If a non-empty response drops every region, throw so the
// caller fails the refresh and keeps what is registered instead of replacing it with nothing.
internal fun GeofenceApiResponse.toDomainRegions(
    polygonSupport: PolygonSupport = PolygonSupport.Disabled
): List<GeofenceRegion> {
    if (geofences.isEmpty()) return emptyList()

    val mapped = geofences.mapNotNull { region ->
        try {
            region.toDomain(polygonSupport) ?: run {
                // A geometry-bearing record logs its own, more specific reason inside [toDomain];
                // a blank id never reaches that branch, so it still reports here.
                if (region.geometry == null || region.id.isBlank()) {
                    SDKComponent.geofenceLogger.logInvalidRegionDropped(region.id)
                }
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

private fun GeofenceApiConfig.toDomain(): GeofenceConfig {
    val coercedLocalRefresh = localRefreshTriggerRadius?.takeIf { it > 0 }
        ?.coerceIn(
            GeofenceConstants.MIN_LOCAL_REFRESH_RADIUS_METERS,
            GeofenceConstants.MAX_LOCAL_REFRESH_RADIUS_METERS
        )
        ?: GeofenceConstants.FALLBACK_LOCAL_REFRESH_RADIUS_METERS
    // 0 disables the cap. A cap below the trigger radius would leave fences inside the trigger but
    // past the cap never re-ranked, so it falls back to the default.
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
        // 0 is a server-side kill switch; 99 leaves one OS slot for the movement trigger.
        maxBusinessGeofences = android?.maxBusinessGeofence?.takeIf { it in 0..99 }
            ?: GeofenceConstants.FALLBACK_MAX_BUSINESS_GEOFENCES,
        maxMonitoringDistance = coercedMaxMonitoringDistance
    )
}

/**
 * A record with `geometry` or `enclosing_circle` never falls back to the flat
 * `latitude`/`longitude`/`radius` fields, which would monitor a circle the backend never asked for.
 */
internal fun GeofenceApiRegion.toDomain(
    polygonSupport: PolygonSupport = PolygonSupport.Disabled
): GeofenceRegion? {
    if (id.isBlank()) return null
    return when (shape?.trim()?.lowercase()?.takeIf(String::isNotEmpty)) {
        null, CIRCLE_SHAPE -> {
            if (geometry != null || enclosingCircle != null) {
                SDKComponent.geofenceLogger.logPolygonDropped(id, PolygonDropReason.UNDESCRIBED_SHAPE)
                null
            } else {
                toCircleRegionOrNull()
            }
        }
        POLYGON_SHAPE -> {
            val polygonGeometry = geometry
            val polygonWakeCircle = enclosingCircle
            if (polygonGeometry == null || polygonWakeCircle == null) {
                SDKComponent.geofenceLogger.logPolygonDropped(id, PolygonDropReason.UNUSABLE_POLYGON)
                null
            } else {
                toPolygonRegionOrNull(polygonGeometry, polygonWakeCircle, polygonSupport)
            }
        }
        else -> {
            SDKComponent.geofenceLogger.logUnsupportedGeometryDropped(id, shape)
            null
        }
    }
}

private fun GeofenceApiRegion.toCircleRegionOrNull(): GeofenceRegion? {
    if (
        radius == null || radius <= 0 ||
        latitude == null || longitude == null ||
        !LocationCoordinates.isValid(latitude, longitude)
    ) {
        return null
    }
    return GeofenceRegion(
        id = id,
        name = name,
        externalId = externalId,
        latitude = latitude,
        longitude = longitude,
        radius = radius.toFloat(),
        transitionTypes = resolveTransitionTypes(transitionTypes),
        lastUpdated = lastUpdated ?: 0L,
        geosetIds = geosetIds,
        metadata = sanitizeMetadata(metadata),
        dwellThresholdSeconds = sanitizeDwellThreshold(dwellThresholdSeconds)
    )
}

private fun GeofenceApiRegion.toPolygonRegionOrNull(
    geometry: GeofenceApiGeometry,
    enclosingCircle: GeofenceApiEnclosingCircle,
    polygonSupport: PolygonSupport
): GeofenceRegion? {
    val logger = SDKComponent.geofenceLogger
    if (!geometry.isPolygonType) {
        logger.logUnsupportedGeometryDropped(id, geometry.type ?: "absent")
        return null
    }
    if (!polygonSupport.isPolygonMonitoringEnabled) {
        logger.logPolygonDroppedUnsupportedRuntime(id)
        return null
    }
    val polygon = geometry.toPolygonGeometryOrNull()
    if (polygon == null) {
        logger.logPolygonDropped(id, PolygonDropReason.RING_UNBUILDABLE)
        return null
    }
    val baseRadiusMeters = enclosingCircle.baseRadiusMeters
    val wakeLatitude = enclosingCircle.latitude
    val wakeLongitude = enclosingCircle.longitude
    val trigger = if (
        wakeLatitude == null || wakeLongitude == null || baseRadiusMeters == null ||
        // Geofence.Builder throws on an out-of-range centre, and registration builds the business
        // batch in one request, so one bad record would fail every fence in the sync.
        !LocationCoordinates.isValid(wakeLatitude, wakeLongitude)
    ) {
        null
    } else {
        PolygonWakeCircleValidator().prepareOrNull(
            PolygonWakeCircle(PolygonCoordinate(wakeLatitude, wakeLongitude), baseRadiusMeters)
        )
    }
    if (trigger == null) {
        logger.logPolygonDropped(id, PolygonDropReason.UNUSABLE_CIRCLE)
        return null
    }
    return GeofenceRegion(
        id = id,
        name = name,
        externalId = externalId,
        latitude = trigger.center.latitude,
        longitude = trigger.center.longitude,
        radius = trigger.radiusMeters,
        // Currently equal to `radius`: `radius` is what GMS registers, `baseRadiusMeters` is what
        // the backend sent and what ranking reads.
        baseRadiusMeters = baseRadiusMeters,
        transitionTypes = resolveTransitionTypes(transitionTypes),
        lastUpdated = lastUpdated ?: 0L,
        geosetIds = geosetIds,
        metadata = sanitizeMetadata(metadata),
        polygonVertices = polygon.vertices,
        dwellThresholdSeconds = sanitizeDwellThreshold(dwellThresholdSeconds)
    )
}

/** Includes records [toDomainRegions] drops, so a rejected record still gets a row. */
internal fun GeofenceApiResponse.toCatalogEntries(): List<GeofenceCatalogEntry> =
    geofences.map { region -> region.toCatalogEntry() }

private fun GeofenceApiRegion.toCatalogEntry(): GeofenceCatalogEntry {
    val claimedShape = shape?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
    val isPolygonRecord = claimedShape == POLYGON_SHAPE
    return GeofenceCatalogEntry(
        id = id,
        name = name,
        geosetIds = geosetIds,
        shape = claimedShape ?: CIRCLE_SHAPE,
        latitude = if (isPolygonRecord) enclosingCircle?.latitude else latitude,
        longitude = if (isPolygonRecord) enclosingCircle?.longitude else longitude,
        radiusMeters = if (isPolygonRecord) enclosingCircle?.baseRadiusMeters else radius,
        vertices = geometry?.toPolygonGeometryOrNull()?.vertices,
        transitionTypes = transitionTypesOrDefaults(transitionTypes).map { it.name.lowercase() }
    )
}

private val GeofenceApiGeometry.isPolygonType: Boolean
    get() = type.equals(POLYGON_GEOMETRY_TYPE, ignoreCase = true)

/** Decoding does not imply monitoring; that also needs [PolygonSupport]. */
internal fun GeofenceApiGeometry.toPolygonGeometryOrNull(): PolygonGeometry? {
    if (!isPolygonType) return null
    val vertices = coordinates?.toPolygonVerticesOrNull() ?: return null
    return PolygonGeometry.fromOrNull(vertices)
}

// GeoJSON positions are [longitude, latitude]; more than one ring means holes (unsupported).
private fun JsonElement.toPolygonVerticesOrNull(): List<PolygonCoordinate>? {
    val rings = this as? JsonArray ?: return null
    if (rings.size != 1) return null
    val outerRing = rings.singleOrNull() as? JsonArray ?: return null
    return outerRing.map { rawPosition ->
        val position = rawPosition as? JsonArray ?: return null
        if (position.size < 2) return null
        val longitude = (position[0] as? JsonPrimitive)?.doubleOrNull ?: return null
        val latitude = (position[1] as? JsonPrimitive)?.doubleOrNull ?: return null
        // A "NaN" literal parses to a Double and passes every comparison-based geometry check, then
        // fails later in the strict cache encoder after the sync has registered.
        if (!longitude.isFinite() || !latitude.isFinite()) return null
        PolygonCoordinate(latitude, longitude)
    }
}

private const val POLYGON_GEOMETRY_TYPE = "Polygon"
private const val CIRCLE_SHAPE = "circle"
private const val POLYGON_SHAPE = "polygon"

private fun sanitizeDwellThreshold(seconds: Long?): Int =
    seconds
        ?.takeIf { it in 1L..GeofenceConstants.MAX_DWELL_THRESHOLD_SECONDS.toLong() }
        ?.toInt()
        ?: 0

/** Scalars only, count/size capped. Keys are sorted so the capping is deterministic. */
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

private fun resolveTransitionTypes(raw: List<String>?): List<GeofenceTransitionType> {
    raw?.forEach { value ->
        if (parseTransitionType(value) == null) SDKComponent.geofenceLogger.logUnknownApiTransitionType(value)
    }
    return transitionTypesOrDefaults(raw)
}

/** [resolveTransitionTypes] without the logging, so the catalog doesn't log each unknown value twice. */
private fun transitionTypesOrDefaults(raw: List<String>?): List<GeofenceTransitionType> {
    val defaults = listOf(GeofenceTransitionType.ENTER, GeofenceTransitionType.EXIT)
    if (raw.isNullOrEmpty()) return defaults
    return raw.mapNotNull(::parseTransitionType).takeIf { it.isNotEmpty() } ?: defaults
}

private fun parseTransitionType(value: String): GeofenceTransitionType? =
    when (value.lowercase()) {
        "enter" -> GeofenceTransitionType.ENTER
        "exit" -> GeofenceTransitionType.EXIT
        else -> null
    }
