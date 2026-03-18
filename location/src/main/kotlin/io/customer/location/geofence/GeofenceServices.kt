package io.customer.location.geofence

/**
 * Public API for geofencing functionality in the Location module.
 *
 * Use [ModuleLocation.locationServices.geofenceServices] after initializing the SDK
 * with [ModuleLocation] and location tracking enabled to access geofencing features.
 *
 * **Important**: Geofencing requires:
 * - Location tracking to be enabled (trackingMode != OFF)
 * - Location permissions (ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION)
 * - Background location permission (ACCESS_BACKGROUND_LOCATION) for Android 10+ if monitoring in background
 * - The host app must request these permissions; the SDK does not request them
 *
 * Example:
 * ```
 * val geofenceServices = ModuleLocation.instance().locationServices.geofenceServices
 *
 * // Add geofences
 * val regions = listOf(
 *     GeofenceRegion(
 *         id = "store_1",
 *         latitude = 37.7749,
 *         longitude = -122.4194,
 *         radius = 100.0,
 *         name = "Downtown Store"
 *     )
 * )
 * geofenceServices.addGeofences(regions)
 *
 * // Remove specific geofences
 * geofenceServices.removeGeofences(listOf("store_1"))
 *
 * // Remove all geofences
 * geofenceServices.removeAllGeofences()
 * ```
 */
interface GeofenceServices {
    /**
     * Adds geofence regions to monitor.
     *
     * If a geofence with the same ID already exists, it will be replaced.
     * Android allows a maximum of 100 geofences per app. If this limit is exceeded,
     * the oldest geofences will be removed to make room for new ones.
     *
     * No-op if location tracking is disabled or location permission is not granted.
     *
     * @param regions List of [GeofenceRegion] to monitor. Empty list is ignored.
     */
    fun addGeofences(regions: List<GeofenceRegion>)

    /**
     * Removes geofences by their IDs.
     *
     * @param ids List of geofence IDs to remove. Empty list or non-existent IDs are ignored.
     */
    fun removeGeofences(ids: List<String>)

    /**
     * Removes all monitored geofences.
     */
    fun removeAllGeofences()

    /**
     * Returns the list of currently monitored geofence regions.
     *
     * @return List of active [GeofenceRegion]. Empty if no geofences are configured.
     */
    fun getActiveGeofences(): List<GeofenceRegion>
}
