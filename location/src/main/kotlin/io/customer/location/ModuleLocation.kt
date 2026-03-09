package io.customer.location

import android.location.Location
import androidx.lifecycle.ProcessLifecycleOwner
import io.customer.location.provider.FusedLocationProvider
import io.customer.location.store.LocationPreferenceStoreImpl
import io.customer.location.sync.LocationSyncFilter
import io.customer.location.sync.LocationSyncStoreImpl
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.subscribe
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.core.pipeline.DataPipeline
import io.customer.sdk.core.pipeline.identifyHookRegistry
import io.customer.sdk.core.util.HandlerMainThreadPoster
import io.customer.sdk.core.util.Logger
import io.customer.sdk.core.util.MainThreadPoster

/**
 * Location module for Customer.io SDK.
 *
 * This module provides location tracking capabilities including:
 * - Manual location setting from host app's existing location system
 * - One-shot SDK-managed location capture
 * - Automatic location capture on app start (ON_APP_START mode)
 *
 * Usage:
 * ```
 * val config = CustomerIOConfigBuilder(appContext, "your-api-key")
 *     .addCustomerIOModule(
 *         ModuleLocation(
 *             LocationModuleConfig.Builder()
 *                 .setLocationTrackingMode(LocationTrackingMode.MANUAL)
 *                 .build()
 *         )
 *     )
 *     .build()
 *
 * CustomerIO.initialize(config)
 *
 * // Manual location from host app
 * ModuleLocation.instance().locationServices.setLastKnownLocation(37.7749, -122.4194)
 *
 * // SDK-managed one-shot location
 * ModuleLocation.instance().locationServices.requestLocationUpdate()
 * ```
 */
class ModuleLocation @JvmOverloads constructor(
    override val moduleConfig: LocationModuleConfig = LocationModuleConfig.Builder().build()
) : CustomerIOModule<LocationModuleConfig> {
    override val moduleName: String = MODULE_NAME

    @Volatile
    private var _locationServices: LocationServicesImpl? = null

    /**
     * Access the location services API.
     *
     * This property is only usable after [CustomerIO.initialize] has been called with
     * [ModuleLocation] registered. The SDK calls [initialize] on all registered modules
     * during startup, which wires up the real implementation.
     *
     * If accessed before initialization (e.g. calling location APIs before
     * [CustomerIO.initialize]), calls will no-op and log an error instead of crashing.
     * This guards against race conditions during app startup or incorrect call order.
     */
    val locationServices: LocationServices
        get() = _locationServices ?: UninitializedLocationServices(SDKComponent.logger)

    override fun initialize() {
        val logger = SDKComponent.logger
        val eventBus = SDKComponent.eventBus
        val context = SDKComponent.android().applicationContext
        val locationScope = SDKComponent.scopeProvider.locationScope

        val dataPipeline = SDKComponent.getOrNull<DataPipeline>()
        val store = LocationPreferenceStoreImpl(context, logger)
        val locationSyncFilter = LocationSyncFilter(
            LocationSyncStoreImpl(context, logger)
        )
        val locationTracker = LocationTracker(dataPipeline, store, locationSyncFilter, logger)

        val locationProvider = FusedLocationProvider(context)
        val orchestrator = LocationOrchestrator(
            config = moduleConfig,
            logger = logger,
            locationTracker = locationTracker,
            locationProvider = locationProvider
        )

        _locationServices = LocationServicesImpl(
            config = moduleConfig,
            logger = logger,
            locationTracker = locationTracker,
            orchestrator = orchestrator,
            scope = locationScope
        )

        // When OFF, skip all background machinery — no restoration, no enrichment,
        // no event subscriptions. LocationServicesImpl has its own isEnabled guards
        // for the public API, so callers get silent no-ops with helpful log messages.
        if (!moduleConfig.isEnabled) return

        val services = _locationServices ?: return

        locationTracker.restorePersistedLocation()

        // Register as IdentifyHook so location is added to identify event context
        // and cleared synchronously during analytics.reset(). This ensures every
        // identify() call carries the device's current location in the event context —
        // the primary way location reaches a user's profile.
        SDKComponent.identifyHookRegistry.register(locationTracker)

        // On identify, attempt to send a supplementary "CIO Location Update" track event.
        // The identify event itself already carries location via context enrichment —
        // this track event is for journey/segment triggers in the user's timeline.
        eventBus.subscribe<Event.UserChangedEvent> {
            if (!it.userId.isNullOrEmpty()) {
                locationTracker.onUserIdentified()
            }
        }

        // Register lifecycle observer for background cancellation and ON_APP_START.
        // - onStop: cancels in-flight GPS requests when the app enters background.
        // - onStart: triggers a one-shot location request on first foreground entry
        //   when trackingMode is ON_APP_START (guaranteed foreground execution).
        // Registration must happen on the main thread.
        val mainThreadPoster: MainThreadPoster = HandlerMainThreadPoster()
        mainThreadPoster.post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LocationLifecycleObserver(services, moduleConfig.trackingMode)
            )
        }
    }

    companion object {
        const val MODULE_NAME: String = "Location"

        /**
         * Returns the initialized [ModuleLocation] instance.
         *
         * @throws IllegalStateException if the module hasn't been registered with the SDK
         */
        @JvmStatic
        fun instance(): ModuleLocation {
            return SDKComponent.modules[MODULE_NAME] as? ModuleLocation
                ?: throw IllegalStateException("ModuleLocation not initialized. Add ModuleLocation to CustomerIOConfigBuilder before calling CustomerIO.initialize().")
        }
    }
}

/**
 * No-op fallback returned when [ModuleLocation.locationServices] is accessed
 * before the SDK has been initialized. Logs an error for each call to help
 * developers diagnose incorrect call order during development.
 */
private class UninitializedLocationServices(
    private val logger: Logger
) : LocationServices {

    private fun logNotInitialized() {
        logger.error("Location module is not initialized. Call CustomerIO.initialize() with ModuleLocation before using location APIs.")
    }

    override fun setLastKnownLocation(latitude: Double, longitude: Double) = logNotInitialized()

    override fun setLastKnownLocation(location: Location) = logNotInitialized()

    override fun requestLocationUpdate() = logNotInitialized()
}
