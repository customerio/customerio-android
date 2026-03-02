package io.customer.location.type

/**
 * Reason location was not delivered. Returned from a location request on failure.
 */
internal enum class LocationProviderError {
    /** Location permission not granted (denied or never requested). */
    PERMISSION_DENIED,

    /** Location services are disabled in device settings. */
    SERVICES_DISABLED,

    /** Location request timed out or failed due to a transient error. */
    TIMEOUT
}
