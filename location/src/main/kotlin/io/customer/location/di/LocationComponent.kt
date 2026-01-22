package io.customer.location.di

import io.customer.location.permission.LocationPermissionsHelper
import io.customer.location.store.LocationPreferenceStore
import io.customer.location.tracking.TrackingEligibilityChecker
import io.customer.sdk.core.di.DiGraph
import io.customer.sdk.core.di.SDKComponent

/**
 * Dependency injection component for the Location module.
 *
 * Provides singleton instances of location-related dependencies
 * that can be accessed throughout the module.
 */
internal object LocationComponent : DiGraph() {

    private val androidComponent
        get() = SDKComponent.android()

    private val applicationContext
        get() = androidComponent.applicationContext

    /**
     * Preference store for location module settings.
     */
    val preferenceStore: LocationPreferenceStore
        get() = singleton { LocationPreferenceStore(applicationContext) }

    /**
     * Permission helper for checking location permission status.
     */
    val permissionsHelper: LocationPermissionsHelper
        get() = singleton { LocationPermissionsHelper(preferenceStore) }

    /**
     * Checker for determining if location tracking is eligible.
     */
    val trackingEligibilityChecker: TrackingEligibilityChecker
        get() = singleton { TrackingEligibilityChecker(applicationContext, preferenceStore, permissionsHelper) }

    /**
     * Resets all location component dependencies.
     * Called when the SDK is reset.
     */
    override fun reset() {
        preferenceStore.clearAll()
        super.reset()
    }
}
