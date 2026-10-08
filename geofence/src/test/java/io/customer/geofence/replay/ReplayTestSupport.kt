package io.customer.geofence.replay

import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.commontest.util.DispatchersProviderStub
import io.customer.geofence.GeofenceBootReceiver
import io.customer.geofence.GeofenceDiagnostics
import io.customer.geofence.GeofenceForegroundCoordinator
import io.customer.geofence.GeofenceLaunchReason
import io.customer.geofence.GeofenceLocationMode
import io.customer.geofence.GeofencePermissionChecker
import io.customer.geofence.GeofenceRegistrar
import io.customer.geofence.api.GeofenceApiService
import io.customer.geofence.di.geofenceApiService
import io.customer.geofence.di.geofenceCooldownStore
import io.customer.geofence.di.geofenceCrossingPipeline
import io.customer.geofence.di.geofenceEventScheduler
import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.di.geofenceManager
import io.customer.geofence.di.geofencePermissionChecker
import io.customer.geofence.di.geofenceRegionStore
import io.customer.geofence.di.geofenceServices
import io.customer.geofence.di.geofenceTransitionEmitter
import io.customer.geofence.di.pendingGeofenceDeliveryStore
import io.customer.geofence.di.polygonGeofenceServiceController
import io.customer.geofence.polygon.PolygonFreshFixSource
import io.customer.geofence.worker.GeofenceEventScheduler
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.clock
import io.customer.sdk.core.util.Clock
import io.customer.sdk.core.util.Logger
import io.customer.sdk.core.util.ScopeProvider
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.launch
import org.junit.After

/** Shared production composition for recorded drives and synthetic replay regressions. */
abstract class ReplayTestSupport : RobolectricTest() {
    internal val virtualClock = VirtualClock()

    internal val boundaryGate = ReplayBoundaryGate(virtualClock)
    internal val api = ReplayApiService(boundaryGate)
    internal val registrar = ReplayRegistrar(boundaryGate)
    internal val fakeLocationServices = ReplayLocationServices()
    internal val replayLogger = ReplayLogger()
    internal val identity = ReplayRunner.MutableIdentity()

    // Not `ScopeProviderStub`: the runner needs one shared scheduler to run what a release made runnable.
    internal var fakeScopeProvider = ReplayScopeProvider()
        private set

    internal val fakeSecureUserStore: SecureUserStore = mockk(relaxed = true) {
        every { getUserId() } answers { identity.userId }
    }

    // Delivery is out of scope: the harness grades detection and acceptance, not rows reaching the
    // backend.
    internal val scheduler: GeofenceEventScheduler = mockk(relaxed = true)

    // Permission is an OS fact, and every recorded drive was captured with it granted.
    internal val permissionChecker: GeofencePermissionChecker = mockk(relaxed = true) {
        every { hasRequiredLocationPermissions() } returns true
        every { isBackgroundDeliveryAvailable() } returns true
    }

    internal val replayFreshFix = ReplayPolygonFreshFixSource()

    override fun setup(testConfig: TestConfig) {
        composeSDK()
        // These clears belong only to a new scenario, never to a process relaunch within one.
        SDKComponent.android().geofenceRegionStore.clearAll()
        SDKComponent.android().geofenceCooldownStore.clearAll()
        SDKComponent.android().pendingGeofenceDeliveryStore.removeAll()
        replayLogger.clear()
    }

    private fun composeSDK() {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
                diGraph {
                    sdk {
                        overrideDependency<Clock>(virtualClock)
                        overrideDependency<Logger>(replayLogger)
                        overrideDependency<ScopeProvider>(fakeScopeProvider)
                        overrideDependency<GeofenceApiService>(api)
                    }
                    android {
                        overrideDependency<GeofenceRegistrar>(registrar)
                        overrideDependency<GeofenceEventScheduler>(scheduler)
                        overrideDependency<GeofencePermissionChecker>(permissionChecker)
                        overrideDependency<SecureUserStore>(fakeSecureUserStore)
                        overrideDependency<PolygonFreshFixSource>(replayFreshFix)
                    }
                }
            }
        )
        GeofenceDiagnostics.setEnabledForTesting(true)
        assertComposedWith(
            "SecureUserStore" to (SDKComponent.android().secureUserStore to fakeSecureUserStore),
            "ScopeProvider" to (SDKComponent.scopeProvider to fakeScopeProvider),
            "Clock" to (SDKComponent.clock to virtualClock),
            "Logger" to (SDKComponent.logger to replayLogger),
            "GeofenceRegistrar" to (SDKComponent.android().geofenceManager to registrar),
            "GeofenceApiService" to (SDKComponent.geofenceApiService to api),
            "GeofenceEventScheduler" to (SDKComponent.android().geofenceEventScheduler to scheduler),
            "GeofencePermissionChecker" to (SDKComponent.android().geofencePermissionChecker to permissionChecker)
        )
    }

    private fun runtime() = ReplayRunner.Runtime(
        scope = fakeScopeProvider.geofenceScope,
        pipeline = SDKComponent.android().geofenceCrossingPipeline,
        services = SDKComponent.android().geofenceServices,
        foreground = foregroundCoordinator()
    )

    private fun reenterProcess(): ReplayRunner.Runtime {
        val scheduler = fakeScopeProvider.scheduler
        fakeScopeProvider.stop()
        SDKComponent.reset()
        fakeScopeProvider = ReplayScopeProvider(scheduler)
        composeSDK()
        return runtime()
    }

    /** The same launch consumers used by ModuleGeofence, without attaching a live location module. */
    private fun initializeModule(runtime: ReplayRunner.Runtime) {
        val android = SDKComponent.android()
        SDKComponent.geofenceLogger.logModuleInitialized(GeofenceLaunchReason.APP_START)
        runtime.scope.launch {
            android.geofenceTransitionEmitter.recoverPendingTransitions()
            if (!fakeSecureUserStore.getUserId().isNullOrEmpty()) {
                android.polygonGeofenceServiceController.beginUserSessionForCurrentUser()
                val anchor = runtime.foreground.anchor()
                runtime.services.onAppLaunch(anchor?.latitude, anchor?.longitude)
                runtime.foreground.autoAcquireIfNeeded(anchor)
            }
        }
    }

    /** Identify consumes the persisted registration anchor, just as ModuleGeofence does. */
    private fun identifyUser(runtime: ReplayRunner.Runtime) {
        val android = SDKComponent.android()
        runtime.scope.launch {
            val userId = fakeSecureUserStore.getUserId()?.takeIf { it.isNotEmpty() } ?: return@launch
            android.polygonGeofenceServiceController.beginUserSession(userId)
            val anchor = runtime.foreground.anchor()
            runtime.services.onUserIdentified(anchor?.latitude, anchor?.longitude)
            runtime.foreground.autoAcquireIfNeeded(anchor)
        }
    }

    private fun restoreBoot(runtime: ReplayRunner.Runtime) {
        SDKComponent.geofenceLogger.logModuleWoke(GeofenceLaunchReason.BOOT_RESTORE)
        runtime.scope.launch { GeofenceBootReceiver().restore() }
    }

    @After
    fun resetDiagnostics() {
        fakeScopeProvider.stop()
        GeofenceDiagnostics.setEnabledForTesting(null)
    }

    internal fun foregroundCoordinator() = GeofenceForegroundCoordinator(
        services = SDKComponent.android().geofenceServices,
        secureUserStore = fakeSecureUserStore,
        locationServices = fakeLocationServices,
        regionStore = SDKComponent.android().geofenceRegionStore,
        lastKnownLocation = { fakeLocationServices.lastKnown },
        locationMode = GeofenceLocationMode.AUTOMATIC,
        logger = SDKComponent.geofenceLogger,
        // Unconfined, so `onForeground`'s dispatcher hop runs inline, not on an IO thread the
        // replay outruns.
        dispatchers = DispatchersProviderStub()
    )

    internal fun runner() = ReplayRunner(
        gate = boundaryGate,
        scheduler = fakeScopeProvider.scheduler,
        api = api,
        registrar = registrar,
        runtime = runtime(),
        reenterProcess = ::reenterProcess,
        initializeModule = ::initializeModule,
        identifyUser = ::identifyUser,
        restoreBoot = ::restoreBoot,
        identity = identity,
        freshFix = replayFreshFix,
        assertDelivery = { record ->
            ReplayDeliveryAssertions.compare(
                record,
                SDKComponent.android().pendingGeofenceDeliveryStore.loadAllOrNull(),
                virtualClock.currentTimeSeconds() - virtualClock.elapsedSeconds.toLong()
            )
        }
    )
}
