package io.customer.geofence

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * `addGeofences()` only requires `ACCESS_FINE_LOCATION`. On API 29+, `ACCESS_BACKGROUND_LOCATION`
 * only gates delivery while the app is backgrounded.
 */
internal class GeofencePermissionChecker(
    private val context: Context
) {
    fun hasRequiredLocationPermissions(): Boolean = hasFineLocationPermission()

    fun hasFineLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    fun isBackgroundDeliveryAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
