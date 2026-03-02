package io.customer.location.type

/**
 * Reason location was not delivered. Returned from a location request on failure.
 */
internal enum class LocationProviderError {
    /** Location permission has not been requested yet. */
    PERMISSION_NOT_DETERMINED,

    /** Location permission explicitly denied by the user. */
    PERMISSION_DENIED,

    /** Location services are disabled in device settings. */
    SERVICES_DISABLED,

    /** Location request timed out or failed due to a transient error. */
    TIMEOUT
}
