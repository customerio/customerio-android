package io.customer.geofence

import androidx.lifecycle.ProcessLifecycleOwner
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.geofence.di.geofenceDeliveryFlusher
import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.di.geofencePermissionReporter
import io.customer.geofence.di.geofenceRegionStore
import io.customer.geofence.di.geofenceServices
import io.customer.geofence.di.geofenceTransitionEmitter
import io.customer.geofence.di.polygonGeofenceServiceController
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
 * to the CDP, and the local set is refreshed when the user moves far enough. A polygon or
 * movement-trigger callback whose fix is not decisive may open a bounded, balanced-power location
 * session. Registering polygons alone does not start sampling, and no foreground service is started.
 *
 * Requires [ModuleLocation] to be registered alongside it — geofencing uses its
 * location provider regardless of the location tracking mode, and works even when
 * location tracking is OFF (geofence fixes never emit analytics). With the default
 * [GeofenceLocationMode.AUTOMATIC] the SDK acquires the location it needs on its own;
 * with [GeofenceLocationMode.MANUAL] the host drives it via [refreshFromCurrentLocation].
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

        // Without the location module no fix can reach the SDK, so nothing would ever sync. Log the
        // misconfiguration and bail before installing subscriptions that would never deliver.
        val locationModule = runCatching { ModuleLocation.instance() }.getOrNull()
        if (locationModule == null) {
            runCatching { SDKComponent.android().polygonGeofenceServiceController.stopAll() }
            logger.logMissingLocationModule()
            return
        }

        // Tells the app-start path apart in logs; the boot receiver marks its own, and a geofence
        // wake announces itself via os.callback.received.
        logger.logModuleInitialized(GeofenceLaunchReason.APP_START)

        val eventBus = SDKComponent.eventBus
        val sdkAndroid = SDKComponent.android()

        // Here as well as on foreground entry, since a cold background wake never foregrounds. The
        // shared reporter dedupes the two.
        sdkAndroid.geofencePermissionReporter.reportIfChanged()

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

    /** Foreground and anchor decisions, built here so a test can drive them without a lifecycle owner. */
    @OptIn(InternalCustomerIOApi::class)
    internal fun foregroundCoordinator(
        sdkAndroid: AndroidSDKComponent,
        locationModule: ModuleLocation
    ): GeofenceForegroundCoordinator = GeofenceForegroundCoordinator(
        services = sdkAndroid.geofenceServices,
        secureUserStore = sdkAndroid.secureUserStore,
        locationServices = locationModule.locationServices,
        regionStore = sdkAndroid.geofenceRegionStore,
        lastKnownLocation = { locationModule.lastKnownLocationOrNull() },
        locationMode = moduleConfig.locationMode,
        logger = SDKComponent.geofenceLogger,
        dispatchers = SDKComponent.dispatchersProvider
    )

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
        eventBus.subscribe<Event.LocationFixAcquired> {
            // Logged before the handler: a discarded fix is still a fix that arrived.
            SDKComponent.geofenceLogger.logLocationFix(it.latitude, it.longitude)
            sdkAndroid.geofenceServices.onLocationAcquired(
                latitude = it.latitude,
                longitude = it.longitude,
                quality = GeofenceFixQuality(fixElapsedRealtimeMillis = it.fixElapsedRealtimeMillis)
            )
        }

        // On identify, prime the geofence pipeline so the new user's session has its
        // nearby set fetched, anchored at the last registration center or cached location.
        eventBus.subscribe<Event.UserChangedEvent> {
            val userId = it.userId
            if (!userId.isNullOrEmpty()) {
                // A throw would cancel this subscription for the rest of the process:
                // `EventBusImpl.subscribe` collects inside a bare `launch`.
                try {
                    SDKComponent.geofenceLogger.logIdentityChanged(identified = true)
                    sdkAndroid.polygonGeofenceServiceController.beginUserSession(userId)
                    val coordinator = foregroundCoordinator(sdkAndroid, locationModule)
                    val anchor = coordinator.anchor()
                    sdkAndroid.geofenceServices.onUserIdentified(
                        latitude = anchor?.latitude,
                        longitude = anchor?.longitude
                    )
                    coordinator.autoAcquireIfNeeded(anchor)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    SDKComponent.geofenceLogger.logSyncFailed("identify refresh failed: ${e.message}")
                }
            }
        }

        // Sign-out: clear user-scoped geofence state so the next identity inherits nothing.
        // ResetEvent fires from `clearIdentify()` before `UserChangedEvent(null)`, so it is the
        // explicit "wipe user state" signal.
        eventBus.subscribe<Event.ResetEvent> {
            // Not on the UserChangedEvent(null) that follows: one sign-out, one record.
            SDKComponent.geofenceLogger.logIdentityChanged(identified = false)
            sdkAndroid.geofenceServices.onUserSignedOut()
        }
    }

    /**
     * Registers foreground-driven geofence work on the main thread. Posting defers it past the
     * SDK's synchronous module-init loop, so ModuleLocation has initialized even when geofence is
     * registered ahead of it. ProcessLifecycleOwner registration needs the main thread anyway.
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
                    permissionReporter = sdkAndroid.geofencePermissionReporter,
                    logger = logger,
                    onForeground = {
                        // Off the main thread: deciding whether to take a fix needs the identity read,
                        // a Keystore decrypt that can block for hundreds of ms on some OEMs.
                        val foregroundScope = SDKComponent.scopeProvider.geofenceScope
                        foregroundScope.launch {
                            try {
                                sdkAndroid.polygonGeofenceServiceController.recover()
                                foregroundCoordinator(sdkAndroid, locationModule).onForeground()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                SDKComponent.geofenceLogger.logSyncFailed("foreground refresh failed: ${e.message}")
                            } finally {
                                foregroundScope.cancel()
                            }
                        }
                    }
                )
            )

            // Defensive sync for a user persisted from a previous session; the freshness threshold
            // makes it a cheap no-op when identify follows. Off the main thread: the reads below
            // include a Keystore decrypt that can block for hundreds of ms on some OEMs.
            val launchScope = SDKComponent.scopeProvider.geofenceScope
            launchScope.launch {
                try {
                    sdkAndroid.geofenceTransitionEmitter.recoverPendingTransitions()
                    val existingUserId = sdkAndroid.secureUserStore.getUserId()
                    if (!existingUserId.isNullOrEmpty()) {
                        // Re-read rather than reuse the id above: an identify can land in between,
                        // and reopening the older owner would clear the routing it just armed.
                        sdkAndroid.polygonGeofenceServiceController.beginUserSessionForCurrentUser()
                        val coordinator = foregroundCoordinator(sdkAndroid, locationModule)
                        val anchor = coordinator.anchor()
                        sdkAndroid.geofenceServices.onAppLaunch(
                            latitude = anchor?.latitude,
                            longitude = anchor?.longitude
                        )
                        coordinator.autoAcquireIfNeeded(anchor)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Keystore/prefs reads can throw on some OEMs; log instead of relying on
                    // the scope's last-resort handler so the failure is attributable.
                    SDKComponent.geofenceLogger.logSyncFailed("app-launch refresh failed: ${e.message}")
                } finally {
                    // One-shot: geofenceScope mints a fresh scope per access, so cancel it once
                    // the launch read completes rather than leaking its Job.
                    launchScope.cancel()
                }
            }
        }
    }

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
