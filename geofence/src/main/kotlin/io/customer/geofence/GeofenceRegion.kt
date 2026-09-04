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
 * A geographic region to monitor for enter/exit transitions.
 *
 * `id` is the OS request ID — derived from the backend's numeric geofence ID
 * so it stays stable and matches the `geofenceId` key on transition events.
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
     * The backend's own wake-circle radius for a polygon, before the platform margin. [radius] is
     * what GMS registers; ranking needs the canonical circle, so both are kept. Null for circles,
     * whose [radius] is already the backend's.
     */
    @SerialName("baseRadiusMeters")
    val baseRadiusMeters: Double? = null
) {
    val isPolygon: Boolean
        get() = polygonVertices != null

    /**
     * Validated geometry for the stored ring, or `null` when it doesn't validate.
     *
     * Vertices reach a region already validated, but they also survive a round trip through the
     * cache, so this re-checks rather than trusting the file. Callers must handle `null` by skipping
     * the region: falling back to the circle fields would monitor the enclosing trigger circle as
     * though it were the polygon.
     */
    fun polygonGeometryOrNull(): PolygonGeometry? =
        polygonVertices?.let(PolygonGeometry::fromOrNull)
}

/** Transition types a geofence can monitor, mapped to GMS constants. */
@Serializable
internal enum class GeofenceTransitionType(val gmsValue: Int) {
    @SerialName("enter")
    ENTER(Geofence.GEOFENCE_TRANSITION_ENTER),

    @SerialName("exit")
    EXIT(Geofence.GEOFENCE_TRANSITION_EXIT)
}

/**
 * Straight-line distance in meters from this region's center to the given coordinates.
 *
 * @throws IllegalArgumentException if coordinates are out of range
 * (latitude must be -90..90, longitude must be -180..180).
 * Callers should validate coordinates at the API boundary.
 */
internal fun GeofenceRegion.distanceTo(lat: Double, lng: Double): Float {
    val result = FloatArray(1)
    Location.distanceBetween(latitude, longitude, lat, lng, result)
    return result[0]
}

/**
 * Straight-line distance in meters from this region's *boundary* to the given coordinates, `0` when
 * they fall inside the region; `null` when the region is a polygon whose geometry is unusable.
 *
 * Relevance for monitoring is proximity to the boundary, not to the center: ranking on center
 * distance evicts a region the device currently occupies once enough regions have nearer centers,
 * and an unmonitored region can never report its exit.
 *
 * A polygon with no usable geometry has no boundary to measure to, and its circle fields describe
 * the coarse trigger rather than the fence — so it reports no distance at all and the caller drops
 * it, instead of ranking (and then registering) an area the backend never sent.
 *
 * [polygonGeometry] lets a caller that already validated this region's ring pass it back in; the
 * default re-derives it.
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

/** Containment against the real shape. Unusable polygon geometry answers `false` — never "inside". */
internal fun GeofenceRegion.contains(latitude: Double, longitude: Double): Boolean = if (isPolygon) {
    val relation = polygonGeometryOrNull()?.relationTo(PolygonCoordinate(latitude, longitude))
    relation != null && relation != PolygonPointRelation.OUTSIDE
} else {
    distanceTo(latitude, longitude) <= radius
}

/**
 * Converts the SDK transition types to a GMS bitmask for [Geofence.Builder.setTransitionTypes].
 * E.g., [ENTER, EXIT] → GEOFENCE_TRANSITION_ENTER | GEOFENCE_TRANSITION_EXIT.
 */
internal fun GeofenceRegion.toGmsTransitionTypes(): Int {
    var mask = 0
    transitionTypes.forEach { mask = mask or it.gmsValue }
    return mask
}

/**
 * True when two regions match on the fields GMS registers (id, coordinates, radius, transition types).
 * A change to only the event/bookkeeping fields (name, geosets, metadata, etc.) skips a re-register
 * that would otherwise fire a spurious `INITIAL_TRIGGER_ENTER`.
 */
internal fun GeofenceRegion.equalsForRegistration(other: GeofenceRegion): Boolean =
    id == other.id &&
        latitude == other.latitude &&
        longitude == other.longitude &&
        radius == other.radius &&
        transitionTypes == other.transitionTypes

/** Stable, process-independent revision for invalidating detections produced by replaced geometry. */
internal fun GeofenceRegion.transitionRevision(): Int {
    var result = id.hashCode()
    result = 31 * result + latitude.hashCode()
    result = 31 * result + longitude.hashCode()
    result = 31 * result + radius.hashCode()
    result = 31 * result + (polygonVertices?.hashCode() ?: 0)
    return result
}
