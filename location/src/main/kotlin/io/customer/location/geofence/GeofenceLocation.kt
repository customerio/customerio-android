package io.customer.location.geofence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Minimal lat/lng holder used to persist anchor points for tiered refresh. */
@Serializable
internal data class GeofenceLocation(
    @SerialName("latitude") val latitude: Double,
    @SerialName("longitude") val longitude: Double
)

/** Straight-line distance in meters from this location to the given coordinates. */
internal fun GeofenceLocation.distanceTo(lat: Double, lng: Double): Float {
    val result = FloatArray(1)
    android.location.Location.distanceBetween(latitude, longitude, lat, lng, result)
    return result[0]
}
