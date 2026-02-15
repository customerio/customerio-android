package io.customer.location.provider

import io.customer.location.type.AuthorizationStatus
import io.customer.location.type.LocationGranularity
import io.customer.location.type.LocationSnapshot

/**
 * Abstracts system location services. Implementations wrap platform-specific
 * location providers (e.g. FusedLocationProviderClient).
 *
 * This component does not request location permission. The host app must handle
 * runtime permission requests and only call location APIs once authorized.
 */
internal interface LocationProvider {
    /**
     * One-shot location request. Returns the location result directly.
     *
     * @param granularity desired accuracy level for the request
     * @return the captured location snapshot
     * @throws LocationRequestException if location could not be obtained
     */
    suspend fun requestLocation(granularity: LocationGranularity): LocationSnapshot

    /**
     * Cancels any in-flight location request. Idempotent - safe to call
     * even when no request is in progress.
     */
    suspend fun cancelRequestLocation()

    /**
     * Current authorization state for location access.
     * Used for pre-checks before requesting location.
     */
    suspend fun currentAuthorizationStatus(): AuthorizationStatus
}
