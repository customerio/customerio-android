package io.customer.location.consent

/**
 * Represents the current eligibility status for location tracking.
 *
 * This sealed class provides a unified way to understand why location
 * tracking may or may not be available, enabling wrapper SDKs to
 * provide appropriate user feedback.
 */
sealed class LocationTrackingEligibility {
    /**
     * Location tracking is eligible - tracking enabled, permission granted, services enabled.
     */
    object Eligible : LocationTrackingEligibility()

    /**
     * Location tracking has not been enabled via [ModuleLocation.setTrackingEnabled].
     * The app needs to call setTrackingEnabled(true) to enable tracking.
     */
    object NotEnabled : LocationTrackingEligibility()

    /**
     * Location permission has not been granted by the user.
     * The app needs to request location permission from the user.
     */
    object PermissionRequired : LocationTrackingEligibility()

    /**
     * Device location services are disabled in system settings.
     * The user needs to enable location services in their device settings.
     */
    object LocationServicesDisabled : LocationTrackingEligibility()
}
