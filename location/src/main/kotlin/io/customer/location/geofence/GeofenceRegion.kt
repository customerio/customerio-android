package io.customer.location.geofence

import io.customer.location.geofence.GeofenceConstants.DEFAULT_RADIUS_METERS

/**
 * Represents a geographic region to monitor for entry, exit, and dwell events.
 *
 * @property id Unique identifier for this geofence region (required).
 * @property latitude Latitude of the geofence center in degrees (required).
 * @property longitude Longitude of the geofence center in degrees (required).
 * @property radius Radius of the geofence in meters. Default is [DEFAULT_RADIUS_METERS].
 * @property name Human-readable name for this geofence (optional).
 * @property customData Additional metadata to include in geofence events (optional).
 * @property dwellTimeMs Minimum time in milliseconds a device must remain in the geofence
 *                       to trigger a dwell event. Default is [GeofenceConstants.DEFAULT_DWELL_TIME_MS].
 */
data class GeofenceRegion(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Double = DEFAULT_RADIUS_METERS,
    val name: String? = null,
    val customData: Map<String, Any>? = null,
    val dwellTimeMs: Long = GeofenceConstants.DEFAULT_DWELL_TIME_MS
) {
    init {
        require(id.isNotBlank()) { "Geofence ID cannot be blank" }
        require(latitude in -90.0..90.0) { "Latitude must be between -90 and 90 degrees" }
        require(longitude in -180.0..180.0) { "Longitude must be between -180 and 180 degrees" }
        require(radius > 0) { "Radius must be greater than 0 meters" }
        require(dwellTimeMs >= 0) { "Dwell time must be non-negative" }
    }
}
