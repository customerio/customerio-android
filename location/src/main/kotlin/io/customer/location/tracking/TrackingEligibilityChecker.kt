package io.customer.location.tracking

import android.content.Context
import io.customer.location.consent.LocationTrackingEligibility
import io.customer.location.permission.LocationPermissionsHelper
import io.customer.location.store.LocationPreferenceStore

/**
 * Determines eligibility for location tracking based on multiple factors.
 *
 * Location tracking requires:
 * 1. Tracking enabled via [setTrackingEnabled] - explicit opt-in from the app
 * 2. OS permission - granted by the user through system dialogs
 * 3. Location services - enabled in device settings
 *
 * This follows the principle of explicit consent: no location data
 * is collected until the app explicitly enables tracking.
 */
internal class TrackingEligibilityChecker(
    private val context: Context,
    private val preferenceStore: LocationPreferenceStore,
    private val permissionsHelper: LocationPermissionsHelper
) {

    /**
     * Gets or sets whether location tracking is enabled.
     *
     * When set to true, the SDK is allowed to track location
     * (if permissions are also granted).
     * When set to false, all location tracking is disabled.
     */
    var isTrackingEnabled: Boolean
        get() = preferenceStore.isTrackingEnabled
        set(value) {
            preferenceStore.isTrackingEnabled = value
        }

    /**
     * Checks if location tracking is currently allowed.
     *
     * Returns true only if:
     * 1. Tracking has been enabled via [isTrackingEnabled]
     * 2. Location permission is granted
     * 3. Device location services are enabled
     */
    fun canTrackLocation(): Boolean {
        if (!isTrackingEnabled) return false
        if (!permissionsHelper.anyLocationPermissionGranted(context)) return false
        if (!permissionsHelper.locationServicesEnabled(context)) return false
        return true
    }

    /**
     * Gets the current tracking eligibility status with reason.
     *
     * Useful for providing user feedback about why tracking is disabled.
     */
    fun getTrackingEligibility(): LocationTrackingEligibility {
        return when {
            !isTrackingEnabled -> LocationTrackingEligibility.NotEnabled
            !permissionsHelper.anyLocationPermissionGranted(context) ->
                LocationTrackingEligibility.PermissionRequired
            !permissionsHelper.locationServicesEnabled(context) ->
                LocationTrackingEligibility.LocationServicesDisabled
            else -> LocationTrackingEligibility.Eligible
        }
    }
}
