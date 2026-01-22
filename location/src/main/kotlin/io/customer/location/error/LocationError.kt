package io.customer.location.error

/**
 * Represents errors that can occur during location operations.
 *
 * These error types are platform-agnostic and consistent across Android and iOS,
 * enabling wrapper SDKs to handle errors uniformly.
 */
sealed class LocationError {
    /**
     * Location permission was denied by the user.
     */
    object PermissionDenied : LocationError() {
        override fun toString(): String = "LocationError.PermissionDenied"
    }

    /**
     * Location tracking has not been enabled via [ModuleLocation.setTrackingEnabled].
     */
    object TrackingDisabled : LocationError() {
        override fun toString(): String = "LocationError.TrackingDisabled"
    }

    /**
     * Device location services are disabled in system settings.
     */
    object LocationServicesDisabled : LocationError() {
        override fun toString(): String = "LocationError.LocationServicesDisabled"
    }

    /**
     * Location service is not available on the device.
     *
     * This covers platform-specific unavailability:
     * - Android: Google Play Services not available
     * - iOS: CoreLocation service issues
     */
    object ServiceUnavailable : LocationError() {
        override fun toString(): String = "LocationError.ServiceUnavailable"
    }

    /**
     * Location request timed out without receiving a location.
     */
    object Timeout : LocationError() {
        override fun toString(): String = "LocationError.Timeout"
    }

    /**
     * An unknown error occurred during location operations.
     *
     * @property cause The underlying exception that caused the error, if available.
     * @property message A descriptive message about the error.
     */
    data class Unknown(
        val cause: Throwable? = null,
        val message: String? = null
    ) : LocationError() {
        override fun toString(): String = "LocationError.Unknown(message=$message, cause=$cause)"
    }
}
