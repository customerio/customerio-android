package io.customer.location.provider

import io.customer.location.type.LocationProviderError

/**
 * Exception thrown when a location request fails.
 * Wraps [LocationProviderError] to provide structured error information.
 */
internal class LocationRequestException(
    val error: LocationProviderError,
    message: String = "Location request failed: $error",
    cause: Throwable? = null
) : Exception(message, cause)
