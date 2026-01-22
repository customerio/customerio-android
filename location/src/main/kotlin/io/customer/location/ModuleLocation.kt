package io.customer.location

import io.customer.location.consent.LocationTrackingEligibility
import io.customer.location.di.LocationComponent
import io.customer.location.permission.LocationPermissionStatus
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.subscribe
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.module.CustomerIOModule

/**
 * Location module for Customer.io SDK.
 *
 * This module provides location tracking capabilities including:
 * - Permission and consent management
 * - One-shot location capture (trackOnce)
 * - Continuous location tracking (start/stop)
 * - Automatic location tracking on initialization and app lifecycle
 *
 * ## Usage
 *
 * ```kotlin
 * // Get the location module instance
 * val location = ModuleLocation.instance()
 *
 * // Enable location tracking
 * location.setLocationTrackingEnabled(enabled = true)
 *
 * // Check permission status
 * when (location.permissionStatus()) {
 *     LocationPermissionStatus.NOT_DETERMINED -> // Request permission
 *     LocationPermissionStatus.DENIED -> // Guide user to settings
 *     LocationPermissionStatus.AUTHORIZED -> // Can track location
 * }
 * ```
 *
 * ## Cross-Platform API
 *
 * This module exposes a unified API that is consistent across Android and iOS,
 * enabling wrapper SDKs (React Native, Flutter, Expo) to use a single interface.
 * Platform-specific details are handled internally.
 */
class ModuleLocation(
    config: LocationModuleConfig
) : CustomerIOModule<LocationModuleConfig> {
    override val moduleName: String = MODULE_NAME
    override val moduleConfig: LocationModuleConfig = config

    private val logger = SDKComponent.logger
    private val eventBus = SDKComponent.eventBus
    private val trackingEligibilityChecker by lazy { LocationComponent.trackingEligibilityChecker }
    private val permissionsHelper by lazy { LocationComponent.permissionsHelper }

    override fun initialize() {
        logger.debug("Location module initialized")

        eventBus.subscribe<Event.ResetEvent> {
            logger.debug("Resetting location module state")
            LocationComponent.reset()
        }
    }

    // region Public API - Cross-Platform

    /**
     * Gets the current location permission status.
     *
     * This method only checks the current state - it does NOT request permissions.
     * The app is responsible for requesting permissions through platform-specific APIs.
     *
     * @return The current [LocationPermissionStatus]
     */
    fun permissionStatus(): LocationPermissionStatus {
        val context = SDKComponent.android().applicationContext
        return permissionsHelper.getPermissionStatus(context)
    }

    /**
     * Checks if device location services are enabled.
     *
     * If this returns false, the user needs to enable location services
     * in their device settings before location tracking can work.
     *
     * @return true if location services are enabled, false otherwise
     */
    fun isLocationServicesEnabled(): Boolean {
        val context = SDKComponent.android().applicationContext
        return permissionsHelper.locationServicesEnabled(context)
    }

    /**
     * Sets whether location tracking is enabled.
     *
     * Location tracking requires both opt-in AND permission.
     * Call this method to enable/disable location tracking from your app.
     *
     * @param enabled true to enable tracking, false to disable
     */
    fun setLocationTrackingEnabled(enabled: Boolean) {
        trackingEligibilityChecker.isTrackingEnabled = enabled
        logger.debug("Location tracking enabled: $enabled")
    }

    /**
     * Checks if location tracking is enabled.
     *
     * @return true if tracking is enabled, false otherwise
     */
    fun isLocationTrackingEnabled(): Boolean {
        return trackingEligibilityChecker.isTrackingEnabled
    }

    /**
     * Checks if location tracking is currently allowed.
     *
     * Returns true only if:
     * 1. Tracking has been enabled via [setLocationTrackingEnabled]
     * 2. Location permission is granted
     * 3. Device location services are enabled
     *
     * @return true if location tracking can proceed, false otherwise
     */
    fun canTrackLocation(): Boolean {
        return trackingEligibilityChecker.canTrackLocation()
    }

    /**
     * Gets the current tracking eligibility status.
     *
     * Useful for providing user feedback about why tracking is disabled.
     *
     * @return The current [LocationTrackingEligibility] status
     */
    fun getTrackingEligibility(): LocationTrackingEligibility {
        return trackingEligibilityChecker.getTrackingEligibility()
    }

    // endregion

    // region Public API - Android-Specific

    /**
     * Gets the list of permissions required for location tracking.
     *
     * This is an Android-specific helper for requesting permissions.
     * Use with `ActivityCompat.requestPermissions()`.
     *
     * @return Array of permission strings (ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)
     */
    fun getRequiredPermissions(): Array<String> {
        return permissionsHelper.getRequiredPermissions()
    }

    /**
     * Call this after requesting location permissions from the user.
     *
     * This helps the SDK distinguish between [LocationPermissionStatus.NOT_DETERMINED]
     * (never asked) and [LocationPermissionStatus.DENIED] (user explicitly denied).
     *
     * Example:
     * ```kotlin
     * ActivityCompat.requestPermissions(activity, location.getRequiredPermissions(), REQ_CODE)
     * location.onPermissionResult() // Call after requesting
     * ```
     */
    fun onPermissionResult() {
        permissionsHelper.markPermissionRequested()
    }

    // endregion

    companion object {
        const val MODULE_NAME: String = "Location"

        /**
         * Gets the singleton instance of the Location module.
         *
         * @throws IllegalStateException if the module has not been initialized
         */
        @JvmStatic
        fun instance(): ModuleLocation {
            return SDKComponent.modules[MODULE_NAME] as? ModuleLocation
                ?: throw IllegalStateException("ModuleLocation not initialized. Make sure to add the location module when initializing CustomerIO.")
        }
    }
}
