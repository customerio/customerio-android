package io.customer.geofence

import androidx.lifecycle.ProcessLifecycleOwner
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.geofence.di.geofenceCooldownFilter
import io.customer.geofence.di.geofenceDeliveryFlusher
import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.di.geofenceServices
import io.customer.location.LocationCoordinates
import io.customer.location.ModuleLocation
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.communication.subscribe
import io.customer.sdk.core.di.AndroidSDKComponent
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.core.util.HandlerMainThreadPoster
import io.customer.sdk.core.util.MainThreadPoster

private const val MODULE_NAME = "Geofence"

/**
 * Geofence module for Customer.io SDK.
 *
 * Registering this module enables on-device geofence monitoring: server-defined
 * geofences are registered with the OS, transitions are persisted and forwarded
 * to the CDP, and the local set is refreshed when the user moves far enough.
 *
 * Requires [ModuleLocation] to be registered alongside it — geofencing reacts
 * to location fixes published by the location module. Customers wanting only
 * the geofencing feature should still register [ModuleLocation] (any tracking
 * mode other than OFF) and feed it location either by calling
 * `setLastKnownLocation` from the host app or letting `ON_APP_START` capture
 * fixes automatically.
 *
 * Usage:
 * ```
 * CustomerIOConfigBuilder(appContext, "your-api-key")
 *     .addCustomerIOModule(ModuleLocation())
 *     .addCustomerIOModule(ModuleGeofence())
 *     .build()
 *     .let(CustomerIO::initialize)
 * ```
 */
class ModuleGeofence @JvmOverloads constructor(
    override val moduleConfig: GeofenceModuleConfig = GeofenceModuleConfig.Builder().build()
) : CustomerIOModule<GeofenceModuleConfig> {
    override val moduleName: String = MODULE_NAME

    override fun initialize() {
        val logger = SDKComponent.geofenceLogger

        // Geofencing is meaningless without the location module: there is no path
        // for fixes to reach the SDK, so nearby-sync and movement triggers would
        // never fire. Surface the misconfiguration as an error and bail before
        // installing subscriptions that would silently never deliver.
        val locationModule = runCatching { ModuleLocation.instance() }.getOrNull()
        if (locationModule == null) {
            logger.logMissingLocationModule()
            return
        }

        val eventBus = SDKComponent.eventBus
        val sdkAndroid = SDKComponent.android()

        subscribeToEvents(eventBus, sdkAndroid, locationModule)
        scheduleForegroundWork(eventBus, sdkAndroid, logger, locationModule)
    }

    /**
     * Subscribe to the SDK-wide events geofencing reacts to: fresh location fixes,
     * identify (prime the new user's nearby set), and sign-out (wipe user state).
     */
    private fun subscribeToEvents(
        eventBus: EventBus,
        sdkAndroid: AndroidSDKComponent,
        locationModule: ModuleLocation
    ) {
        // Recover from a first-run race where identify lands before the first
        // GPS fix: GeofenceServices holds a "last skipped for no-location" flag
        // and re-triggers a refresh when a fresh fix arrives.
        eventBus.subscribe<Event.LocationAcquired> {
            sdkAndroid.geofenceServices.onLocationAcquired(it.latitude, it.longitude)
        }

        // On identify, snapshot the current location and prime the geofence
        // pipeline so the new user's session has its nearby set fetched.
        eventBus.subscribe<Event.UserChangedEvent> {
            if (!it.userId.isNullOrEmpty()) {
                val location = locationModule.lastKnownLocationOrNull()
                sdkAndroid.geofenceServices.onUserIdentified(
                    latitude = location?.latitude,
                    longitude = location?.longitude
                )
            }
        }

        // Sign-out: clear geofence state and cooldown so the next user (or anonymous
        // session) doesn't inherit anything from the previous identity. ResetEvent
        // fires from `clearIdentify()` before `UserChangedEvent(null)`, so it's the
        // explicit "wipe user state" signal — analogous to analytics.reset().
        eventBus.subscribe<Event.ResetEvent> {
            sdkAndroid.geofenceServices.onUserSignedOut()
            sdkAndroid.geofenceCooldownFilter.clearAll()
        }
    }

    /**
     * Register foreground-driven geofence work on the main thread. Posting defers
     * this until after the SDK's synchronous module-init loop: all modules are
     * placed in SDKComponent.modules before any initialize() runs, so reading
     * location synchronously here would hit the not-yet-initialized location
     * services when geofence is registered ahead of location. Posting guarantees
     * ModuleLocation has initialized (and ProcessLifecycleOwner registration must
     * happen on the main thread regardless).
     */
    private fun scheduleForegroundWork(
        eventBus: EventBus,
        sdkAndroid: AndroidSDKComponent,
        logger: GeofenceLogger,
        locationModule: ModuleLocation
    ) {
        val mainThreadPoster: MainThreadPoster = HandlerMainThreadPoster()
        mainThreadPoster.post {
            // Flush pending OS-delivered transitions to the analytics pipeline on
            // every foreground entry (exactly-once against the WorkManager worker).
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                GeofenceLifecycleObserver(
                    deliveryFlusher = sdkAndroid.geofenceDeliveryFlusher,
                    eventBus = eventBus,
                    logger = logger
                )
            )

            // Defensive sync at launch: if a user identified in a previous session is
            // still persisted, kick off a geofence refresh now. The repository's
            // freshness threshold makes this a cheap no-op when identify also fires
            // shortly after init (the common case), so it only does work when the host
            // app doesn't call identify on this launch and the last sync is stale.
            val existingUserId = sdkAndroid.secureUserStore.getUserId()
            if (!existingUserId.isNullOrEmpty()) {
                val cachedLocation = locationModule.lastKnownLocationOrNull()
                sdkAndroid.geofenceServices.onAppLaunch(
                    latitude = cachedLocation?.latitude,
                    longitude = cachedLocation?.longitude
                )
            }
        }
    }
}

@OptIn(InternalCustomerIOApi::class)
private fun ModuleLocation.lastKnownLocationOrNull(): LocationCoordinates? =
    locationServices.getLastKnownLocation()
