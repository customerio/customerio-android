package io.customer.geofence

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ProcessLifecycleOwner
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.geofence.di.geofenceCooldownFilter
import io.customer.geofence.di.geofenceDeliveryFlusher
import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.di.geofenceManager
import io.customer.geofence.di.geofenceRegionStore
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val MODULE_NAME = "Geofence"

/**
 * Geofence module for Customer.io SDK.
 *
 * Registering this module enables on-device geofence monitoring: server-defined
 * geofences are registered with the OS, transitions are persisted and forwarded
 * to the CDP, and the local set is refreshed when the user moves far enough.
 *
 * Requires [ModuleLocation] to be registered alongside it — geofencing uses its
 * location provider regardless of the location tracking mode, and works even when
 * location tracking is OFF (geofence fixes never emit analytics). With the default
 * [GeofenceLocationMode.AUTOMATIC] the SDK acquires the location it needs on its own;
 * with [GeofenceLocationMode.MANUAL] the host drives it via [refreshFromCurrentLocation];
 * with [GeofenceLocationMode.OFF] geofencing is disabled.
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

        if (!moduleConfig.isEnabled) {
            logger.logGeofencingDisabled()
            tearDownForDisabledMode(logger)
            return
        }

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
     * Requests a one-shot location fix and refreshes the nearby geofence set from
     * it, without emitting a "CIO Location Update" analytics event. Works
     * regardless of [GeofenceModuleConfig.locationMode].
     *
     * Call this after the host app has been granted location permission — the
     * primary way to drive geofencing when [GeofenceLocationMode.MANUAL] is set.
     */
    @OptIn(InternalCustomerIOApi::class)
    fun refreshFromCurrentLocation() {
        if (!moduleConfig.isEnabled) return
        val locationModule = runCatching { ModuleLocation.instance() }.getOrNull()
        if (locationModule == null) {
            SDKComponent.geofenceLogger.logMissingLocationModule()
            return
        }
        // Arm first so the returning fix drives a sync even without a prior
        // no-location skip, then request a silent (no-analytics) fix.
        SDKComponent.android().geofenceServices.onRefreshRequested()
        locationModule.locationServices.requestLocationUpdateSilently()
    }

    /**
     * In [GeofenceLocationMode.AUTOMATIC], acquires a silent (no-analytics) fix when none is
     * available; the returning fix drives the sync via [GeofenceServices.onLocationAcquired].
     * MANUAL leaves it to the host. Lives here (not [GeofenceServices]) as it needs [ModuleLocation].
     */
    @VisibleForTesting
    @OptIn(InternalCustomerIOApi::class)
    internal fun autoAcquireIfNeeded(locationModule: ModuleLocation, currentLocation: LocationCoordinates?) {
        if (currentLocation != null) return
        if (moduleConfig.locationMode != GeofenceLocationMode.AUTOMATIC) return
        // No-ops without location permission.
        locationModule.locationServices.requestLocationUpdateSilently()
    }

    /**
     * Undoes a previous run for [GeofenceLocationMode.OFF]: OS registrations and the receivers
     * survive app updates and fire independently of the subscriptions this mode skips, so disabling
     * has to tear down rather than just not set up. Unlike the sign-out wipe the store is cleared
     * unconditionally — no later refresh exists to retry a failed OS clear, and dropping
     * registeredIds is what makes the receiver discard (and unregister) a stray transition.
     */
    private fun tearDownForDisabledMode(logger: GeofenceLogger) {
        // Both guarded: this runs inside the host's `CustomerIO.initialize()`, where a throw would
        // surface as a startup crash.
        val sdkAndroid = runCatching { SDKComponent.android() }.getOrNull() ?: run {
            logger.logSyncFailed("Disabled-mode teardown skipped: Android components unavailable")
            return
        }
        // Cleared synchronously so the receivers — which run on their own scopes later in this launch
        // — can't read state they'd re-register from. `prefs.edit {}` defers only the disk write.
        runCatching { sdkAndroid.geofenceRegionStore.clearUserScopedState() }
            .onFailure { logger.logSyncFailed("Disabled-mode store clear failed: ${it.message}") }

        val teardownScope = SDKComponent.scopeProvider.geofenceScope
        teardownScope.launch {
            try {
                sdkAndroid.geofenceCooldownFilter.clearAll()
                // Pending rows are transitions that already happened; OFF stops producing events
                // rather than retracting past ones. Before the OS clear so a failure can't skip it.
                flushPendingGeofenceDeliveries(
                    deliveryFlusher = sdkAndroid.geofenceDeliveryFlusher,
                    eventBus = SDKComponent.eventBus,
                    regionStore = sdkAndroid.geofenceRegionStore,
                    logger = logger
                )
                sdkAndroid.geofenceManager.clearAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.logSyncFailed("Disabled-mode teardown failed: ${e.message}")
            } finally {
                teardownScope.cancel()
            }
        }
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

        // On identify, prime the geofence pipeline so the new user's session has its
        // nearby set fetched, anchored at the current registration center.
        eventBus.subscribe<Event.UserChangedEvent> {
            if (!it.userId.isNullOrEmpty()) {
                val anchor = refreshAnchor(sdkAndroid, locationModule)
                sdkAndroid.geofenceServices.onUserIdentified(
                    latitude = anchor?.latitude,
                    longitude = anchor?.longitude
                )
                autoAcquireIfNeeded(locationModule, anchor)
            }
        }

        // Sign-out: clear geofence state (and, inside the repository's guarded reset,
        // the cooldown history) so the next user (or anonymous session) doesn't inherit
        // anything from the previous identity. ResetEvent fires from `clearIdentify()`
        // before `UserChangedEvent(null)`, so it's the explicit "wipe user state"
        // signal — analogous to analytics.reset().
        eventBus.subscribe<Event.ResetEvent> {
            sdkAndroid.geofenceServices.onUserSignedOut()
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
            // every foreground entry (at-least-once with the WorkManager worker,
            // deduped downstream by transitionId).
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                GeofenceLifecycleObserver(
                    deliveryFlusher = sdkAndroid.geofenceDeliveryFlusher,
                    eventBus = eventBus,
                    regionStore = sdkAndroid.geofenceRegionStore,
                    logger = logger
                )
            )

            // Defensive sync at launch: if a user identified in a previous session is still
            // persisted, kick off a geofence refresh now (anchored at the registration center).
            // The repository's freshness threshold makes this a cheap no-op when identify also
            // fires shortly after init (the common case). Runs off the main thread — the reads
            // below hit SharedPreferences plus a Keystore decrypt, which can block for hundreds
            // of ms on some OEMs; only the observer registration above needs the main thread.
            val launchScope = SDKComponent.scopeProvider.geofenceScope
            launchScope.launch {
                try {
                    val existingUserId = sdkAndroid.secureUserStore.getUserId()
                    if (!existingUserId.isNullOrEmpty()) {
                        val anchor = refreshAnchor(sdkAndroid, locationModule)
                        sdkAndroid.geofenceServices.onAppLaunch(
                            latitude = anchor?.latitude,
                            longitude = anchor?.longitude
                        )
                        autoAcquireIfNeeded(locationModule, anchor)
                    }
                } finally {
                    // One-shot: geofenceScope mints a fresh scope per access, so cancel it once
                    // the launch read completes rather than leaking its Job.
                    launchScope.cancel()
                }
            }
        }
    }

    private fun refreshAnchor(sdkAndroid: AndroidSDKComponent, locationModule: ModuleLocation): LocationCoordinates? =
        resolveAnchor(
            registrationCenter = sdkAndroid.geofenceRegionStore.getLastMovementTriggerLocation(),
            lastKnown = locationModule.lastKnownLocationOrNull()
        )

    /**
     * Location to anchor an identify/launch refresh at: the last registration center (walked by
     * background movement EXITs) if set, else the location cache. Movement never updates the cache,
     * so on relaunch it can be stale — ranking a refresh from it after the device moved while the
     * app was dead would clobber the good registration with a set ranked around a stale position.
     */
    @VisibleForTesting
    internal fun resolveAnchor(registrationCenter: GeofenceLocation?, lastKnown: LocationCoordinates?): LocationCoordinates? =
        registrationCenter?.let { LocationCoordinates(latitude = it.latitude, longitude = it.longitude) } ?: lastKnown

    companion object {
        /**
         * Returns the initialized [ModuleGeofence] instance.
         *
         * @throws IllegalStateException if the module hasn't been registered with the SDK
         */
        @JvmStatic
        fun instance(): ModuleGeofence {
            return SDKComponent.modules[MODULE_NAME] as? ModuleGeofence
                ?: throw IllegalStateException("ModuleGeofence not initialized. Add ModuleGeofence to CustomerIOConfigBuilder before calling CustomerIO.initialize().")
        }
    }
}

@OptIn(InternalCustomerIOApi::class)
private fun ModuleLocation.lastKnownLocationOrNull(): LocationCoordinates? =
    locationServices.getLastKnownLocation()
