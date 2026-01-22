package io.customer.location.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import io.customer.location.store.LocationPreferenceStore

/**
 * Helper class for checking location permission status.
 *
 * This class only checks permissions - it does NOT request them.
 * The app is responsible for requesting permissions using
 * ActivityCompat.requestPermissions().
 */
internal class LocationPermissionsHelper(
    private val preferenceStore: LocationPreferenceStore
) {

    /**
     * Checks if fine location permission (ACCESS_FINE_LOCATION) is granted.
     */
    fun fineLocationPermissionGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Checks if coarse location permission (ACCESS_COARSE_LOCATION) is granted.
     */
    fun coarseLocationPermissionGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Checks if any location permission (fine or coarse) is granted.
     */
    fun anyLocationPermissionGranted(context: Context): Boolean {
        return fineLocationPermissionGranted(context) || coarseLocationPermissionGranted(context)
    }

    /**
     * Checks if device location services are enabled.
     */
    fun locationServicesEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return LocationManagerCompat.isLocationEnabled(locationManager)
    }

    /**
     * Gets the current permission status.
     */
    fun getPermissionStatus(context: Context): LocationPermissionStatus {
        if (!anyLocationPermissionGranted(context)) {
            return if (preferenceStore.hasRequestedPermission) {
                LocationPermissionStatus.DENIED
            } else {
                LocationPermissionStatus.NOT_DETERMINED
            }
        }
        return LocationPermissionStatus.AUTHORIZED
    }

    /**
     * Marks that permission has been requested.
     * Call this after showing the permission dialog.
     */
    fun markPermissionRequested() {
        preferenceStore.hasRequestedPermission = true
    }

    /**
     * Gets the list of permissions required for location tracking.
     */
    fun getRequiredPermissions(): Array<String> {
        return arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
}
