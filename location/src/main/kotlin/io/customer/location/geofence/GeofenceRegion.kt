package io.customer.location.geofence

import android.location.Location
import com.google.android.gms.location.Geofence
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A geographic region to monitor for enter/exit transitions. */
@Serializable
internal data class GeofenceRegion(
    @SerialName("id") val id: String,
    @SerialName("latitude") val latitude: Double,
    @SerialName("longitude") val longitude: Double,
    @SerialName("radius") val radius: Float,
    @SerialName("name") val name: String = "",
    @SerialName("transitionTypes") val transitionTypes: List<GeofenceTransitionType> = listOf(
        GeofenceTransitionType.ENTER,
        GeofenceTransitionType.EXIT
    ),
    @SerialName("lastUpdated") val lastUpdated: Long = 0L
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
 * Converts the SDK transition types to a GMS bitmask for [Geofence.Builder.setTransitionTypes].
 * E.g., [ENTER, EXIT] → GEOFENCE_TRANSITION_ENTER | GEOFENCE_TRANSITION_EXIT.
 */
internal fun GeofenceRegion.toGmsTransitionTypes(): Int {
    var mask = 0
    transitionTypes.forEach { mask = mask or it.gmsValue }
    return mask
}
