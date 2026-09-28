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
 * Geofence module for Customer.io SDK. Registers server-defined geofences with the OS, forwards
 * transitions to the CDP, and refreshes the set as the user moves. A polygon or movement-trigger
 * callback without a decisive fix may open a bounded, balanced-power location session. Polygons
 * alone do not start sampling, and no foreground service is started.
 *
 * Requires [ModuleLocation], even when location tracking is OFF; geofence fixes never emit
 * analytics. With the default [GeofenceLocationMode.AUTOMATIC] the SDK acquires location itself;
 * with [GeofenceLocationMode.MANUAL] call [refreshFromCurrentLocation].
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

        // Without the location module no fix can reach the SDK, so nothing would ever sync.
        val locationModule = runCatching { ModuleLocation.instance() }.getOrNull()
        if (locationModule == null) {
            runCatching { SDKComponent.android().polygonGeofenceServiceController.stopAll() }
            logger.logMissingLocationModule()
            return
        }

        logger.logModuleInitialized(GeofenceLaunchReason.APP_START)

        val eventBus = SDKComponent.eventBus
        val sdkAndroid = SDKComponent.android()

        // Here as well as on foreground entry, since a cold background wake never foregrounds.
        sdkAndroid.geofencePermissionReporter.reportIfChanged()

        subscribeToEvents(eventBus, sdkAndroid, locationModule)
        scheduleForegroundWork(eventBus, sdkAndroid, logger, locationModule)
    }

    /**
     * Requests a one-shot fix and refreshes nearby geofences from it, without emitting a
     * "CIO Location Update" event. Works in any [GeofenceModuleConfig.locationMode], and is how
     * [GeofenceLocationMode.MANUAL] drives geofencing. Call after location permission is granted.
     */
    @OptIn(InternalCustomerIOApi::class)
    fun refreshFromCurrentLocation() {
        val locationModule = runCatching { ModuleLocation.instance() }.getOrNull()
        if (locationModule == null) {
            SDKComponent.geofenceLogger.logMissingLocationModule()
            return
        }
        // Arm first so the returning fix drives a sync.
        SDKComponent.android().geofenceServices.onRefreshRequested()
        locationModule.locationServices.requestLocationUpdateSilently()
    }

    /** Built here so a test can drive it without a lifecycle owner. */
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

    private fun subscribeToEvents(
        eventBus: EventBus,
        sdkAndroid: AndroidSDKComponent,
        locationModule: ModuleLocation
    ) {
        // Recovers from identify landing before the first fix.
        eventBus.subscribe<Event.LocationFixAcquired> {
            // Logged before the handler: a discarded fix is still a fix that arrived.
            SDKComponent.geofenceLogger.logLocationFix(it.latitude, it.longitude)
            sdkAndroid.geofenceServices.onLocationAcquired(
                latitude = it.latitude,
                longitude = it.longitude,
                quality = GeofenceFixQuality(fixElapsedRealtimeMillis = it.fixElapsedRealtimeMillis)
            )
        }

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

        // The sign-out signal: `clearIdentify()` fires it before `UserChangedEvent(null)`. Logged
        // here, not on that event, so one sign-out is one record.
        eventBus.subscribe<Event.ResetEvent> {
            SDKComponent.geofenceLogger.logIdentityChanged(identified = false)
            sdkAndroid.geofenceServices.onUserSignedOut()
        }
    }

    /**
     * Posting defers past the synchronous module-init loop, so ModuleLocation has initialized even
     * when registered after this module. ProcessLifecycleOwner needs the main thread anyway.
     */
    private fun scheduleForegroundWork(
        eventBus: EventBus,
        sdkAndroid: AndroidSDKComponent,
        logger: GeofenceLogger,
        locationModule: ModuleLocation
    ) {
        val mainThreadPoster: MainThreadPoster = HandlerMainThreadPoster()
        mainThreadPoster.post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                GeofenceLifecycleObserver(
                    deliveryFlusher = sdkAndroid.geofenceDeliveryFlusher,
                    eventBus = eventBus,
                    regionStore = sdkAndroid.geofenceRegionStore,
                    permissionReporter = sdkAndroid.geofencePermissionReporter,
                    logger = logger,
                    onForeground = {
                        // Off the main thread: the identity read is a Keystore decrypt that can block
                        // for hundreds of ms on some OEMs.
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

            // Off the main thread for the same Keystore read.
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
                    // Keystore/prefs reads can throw on some OEMs.
                    SDKComponent.geofenceLogger.logSyncFailed("app-launch refresh failed: ${e.message}")
                } finally {
                    // geofenceScope mints a fresh scope per access; cancel it rather than leak its Job.
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
