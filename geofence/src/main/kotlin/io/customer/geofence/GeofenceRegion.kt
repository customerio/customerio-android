package io.customer.geofence

import android.location.Location
import com.google.android.gms.location.Geofence
import io.customer.geofence.polygon.PolygonCoordinate
import io.customer.geofence.polygon.PolygonGeometry
import io.customer.geofence.polygon.PolygonPointRelation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * `id` is the OS request ID, taken from the backend ID so it stays stable and matches `geofenceId`
 * on events.
 */
@Serializable
internal data class GeofenceRegion(
    @SerialName("id")
    val id: String,
    @SerialName("latitude")
    val latitude: Double,
    @SerialName("longitude")
    val longitude: Double,
    @SerialName("radius")
    val radius: Float,
    @SerialName("name")
    val name: String? = null,
    @SerialName("externalId")
    val externalId: String? = null,
    @SerialName("transitionTypes")
    val transitionTypes: List<GeofenceTransitionType> = listOf(
        GeofenceTransitionType.ENTER,
        GeofenceTransitionType.EXIT
    ),
    @SerialName("lastUpdated")
    val lastUpdated: Long = 0L,
    @SerialName("geosetIds")
    val geosetIds: List<String> = emptyList(),
    @SerialName("metadata")
    val metadata: Map<String, JsonElement> = emptyMap(),
    @SerialName("polygonVertices")
    val polygonVertices: List<PolygonCoordinate>? = null,
    /**
     * Polygon only. Equals [radius] today, but [radius] is what GMS holds and this is what the
     * backend sent.
     */
    @SerialName("baseRadiusMeters")
    val baseRadiusMeters: Double? = null,
    /** Zero disables dwell. Positive values are seconds inside required for one event per visit. */
    @SerialName("dwellThresholdSeconds")
    val dwellThresholdSeconds: Int = 0
) {
    val isPolygon: Boolean
        get() = polygonVertices != null

    /**
     * Re-validates because vertices round-trip through the cache. On `null`, skip the region: its
     * circle fields describe the enclosing trigger circle, not the polygon.
     */
    fun polygonGeometryOrNull(): PolygonGeometry? =
        polygonVertices?.let(PolygonGeometry::fromOrNull)
}

@Serializable
internal enum class GeofenceTransitionType(val gmsValue: Int) {
    @SerialName("enter")
    ENTER(Geofence.GEOFENCE_TRANSITION_ENTER),

    @SerialName("exit")
    EXIT(Geofence.GEOFENCE_TRANSITION_EXIT)
}

/** Meters from the center. Throws for out-of-range coordinates; validate at the API boundary. */
internal fun GeofenceRegion.distanceTo(lat: Double, lng: Double): Float {
    val result = FloatArray(1)
    Location.distanceBetween(latitude, longitude, lat, lng, result)
    return result[0]
}

/**
 * Meters from the boundary, `0` inside, `null` (drop it) for unusable polygon geometry. Boundary, not
 * center, so nearer centers can't evict an occupied region, which would then never report its exit.
 */
internal fun GeofenceRegion.edgeDistanceToOrNull(
    lat: Double,
    lng: Double,
    polygonGeometry: PolygonGeometry? = polygonGeometryOrNull()
): Float? {
    if (!isPolygon) return (distanceTo(lat, lng) - radius).coerceAtLeast(0f)
    val geometry = polygonGeometry ?: return null
    val point = PolygonCoordinate(lat, lng)
    return if (geometry.relationTo(point) != PolygonPointRelation.OUTSIDE) {
        0f
    } else {
        geometry.boundaryDistanceMeters(point).toFloat()
    }
}

/** Against the real shape; unusable polygon geometry is never inside. */
internal fun GeofenceRegion.contains(latitude: Double, longitude: Double): Boolean = if (isPolygon) {
    val relation = polygonGeometryOrNull()?.relationTo(PolygonCoordinate(latitude, longitude))
    relation != null && relation != PolygonPointRelation.OUTSIDE
} else {
    distanceTo(latitude, longitude) <= radius
}

internal fun GeofenceRegion.toGmsTransitionTypes(): Int {
    if (isPolygon) {
        return Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
    }
    var mask = 0
    transitionTypes.forEach { mask = mask or it.gmsValue }
    if (dwellThresholdSeconds > 0 || transitionTypes.contains(GeofenceTransitionType.EXIT)) {
        mask = mask or Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
    }
    if (dwellThresholdSeconds in 1..GeofenceConstants.MAX_DWELL_THRESHOLD_SECONDS) {
        mask = mask or Geofence.GEOFENCE_TRANSITION_DWELL
    }
    return mask
}

/**
 * Only the fields GMS registers, so other edits (name, geosets, metadata) skip a re-register that
 * would fire a spurious `INITIAL_TRIGGER_ENTER`. Polygons always register ENTER and EXIT.
 */
internal fun GeofenceRegion.equalsForRegistration(other: GeofenceRegion): Boolean =
    id == other.id &&
        latitude == other.latitude &&
        longitude == other.longitude &&
        radius == other.radius &&
        isPolygon == other.isPolygon &&
        (isPolygon || transitionTypes == other.transitionTypes) &&
        (isPolygon || dwellThresholdSeconds == other.dwellThresholdSeconds)

/** Stable, process-independent revision for invalidating detections produced by replaced geometry. */
internal fun GeofenceRegion.transitionRevision(): Int {
    var result = id.hashCode()
    result = 31 * result + latitude.hashCode()
    result = 31 * result + longitude.hashCode()
    result = 31 * result + radius.hashCode()
    result = 31 * result + (polygonVertices?.hashCode() ?: 0)
    result = 31 * result + dwellThresholdSeconds
    return result
}
