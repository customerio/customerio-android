package io.customer.geofence

import android.location.Location
import com.google.android.gms.location.Geofence
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
    val metadata: Map<String, JsonElement> = emptyMap()
)

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
 * they fall inside the region.
 *
 * Relevance for monitoring is proximity to the boundary, not to the center: ranking on center
 * distance evicts a region the device currently occupies once enough regions have nearer centers,
 * and an unmonitored region can never report its exit.
 */
internal fun GeofenceRegion.edgeDistanceTo(lat: Double, lng: Double): Float =
    (distanceTo(lat, lng) - radius).coerceAtLeast(0f)

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
