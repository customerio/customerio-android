package io.customer.geofence

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime checks for location permissions related to geofence registration.
 *
 * `GeofencingClient.addGeofences()` only requires `ACCESS_FINE_LOCATION`. On API 29+,
 * `ACCESS_BACKGROUND_LOCATION` separately gates whether transitions are delivered
 * while the app is backgrounded — without it, transitions still fire in the
 * foreground but are dropped once the app is backgrounded.
 */
internal class GeofencePermissionChecker(
    private val context: Context
) {
    fun hasRequiredLocationPermissions(): Boolean = hasFineLocationPermission()

    fun hasFineLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Returns true when background delivery is available — either the OS doesn't
     * require a separate permission (API < 29) or the user has granted it.
     */
    fun isBackgroundDeliveryAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
