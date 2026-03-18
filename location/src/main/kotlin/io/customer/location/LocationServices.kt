package io.customer.location

import android.location.Location
import io.customer.location.geofence.GeofenceServices

/**
 * Public API for the Location module.
 *
 * Use [ModuleLocation.locationServices] after initializing the SDK with [ModuleLocation]
 * to get the instance.
 *
 * Example:
 * ```
 * // Manual location from host app's existing location system
 * ModuleLocation.instance().locationServices.setLastKnownLocation(37.7749, -122.4194)
 *
 * // Or pass an Android Location object
 * ModuleLocation.instance().locationServices.setLastKnownLocation(androidLocation)
 *
 * // Access geofencing services
 * val geofenceServices = ModuleLocation.instance().locationServices.geofenceServices
 * ```
 */
interface LocationServices {
    /**
     * Access to geofencing services.
     *
     * Requires location tracking to be enabled (trackingMode != OFF).
     * The host app must request and obtain location permissions before using geofencing.
     */
    val geofenceServices: GeofenceServices

    /**
     * Sets the last known location from the host app's existing location system.
     *
     * Use this method when your app already manages location and you want to
     * send that data to Customer.io without the SDK managing location permissions
     * or the FusedLocationProviderClient directly.
     *
     * @param latitude the latitude in degrees, must be between -90 and 90
     * @param longitude the longitude in degrees, must be between -180 and 180
     */
    fun setLastKnownLocation(latitude: Double, longitude: Double)

    /**
     * Sets the last known location from an Android [Location] object.
     *
     * Convenience overload for apps that already have a [Location] instance
     * from their own location system.
     *
     * @param location the Android Location object to track
     */
    fun setLastKnownLocation(location: Location)

    /**
     * Requests a single location update and sends the result to Customer.io.
     *
     * No-ops if location tracking is disabled or permission is not granted.
     *
     * The SDK does not request location permission. The host app must request
     * runtime permissions and only call this when permission is granted.
     */
    fun requestLocationUpdate()
}
