package io.customer.location.geofence

import android.location.Location
import com.google.android.gms.location.Geofence

/** A geographic region to monitor for enter/exit transitions. */
internal data class GeofenceRegion(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val name: String = "",
    val transitionTypes: List<GeofenceTransitionType> = listOf(
        GeofenceTransitionType.ENTER,
        GeofenceTransitionType.EXIT
    ),
    val lastUpdated: Long = 0L
)

/** Transition types a geofence can monitor, mapped to GMS constants. */
internal enum class GeofenceTransitionType(val gmsValue: Int) {
    ENTER(Geofence.GEOFENCE_TRANSITION_ENTER),
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
