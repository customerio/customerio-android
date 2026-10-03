package io.customer.geofence.di

import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.LocationServices
import io.customer.geofence.GeofenceBusinessTransitionProcessor
import io.customer.geofence.GeofenceCooldownFilter
import io.customer.geofence.GeofenceCrossingPipeline
import io.customer.geofence.GeofenceDistanceFilter
import io.customer.geofence.GeofenceDwellCoordinator
import io.customer.geofence.GeofenceJsonSerializer
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.GeofenceManager
import io.customer.geofence.GeofencePackageInfo
import io.customer.geofence.GeofencePermissionChecker
import io.customer.geofence.GeofencePermissionReporter
import io.customer.geofence.GeofenceReceiverToggle
import io.customer.geofence.GeofenceRegistrar
import io.customer.geofence.GeofenceRepository
import io.customer.geofence.GeofenceRepositoryImpl
import io.customer.geofence.GeofenceServices
import io.customer.geofence.GeofenceServicesImpl
import io.customer.geofence.GeofenceTransitionEmitter
import io.customer.geofence.api.GeofenceApiService
import io.customer.geofence.api.GeofenceApiServiceImpl
import io.customer.geofence.polygon.AndroidPolygonBootSessionProvider
import io.customer.geofence.polygon.GmsPolygonFreshFixSource
import io.customer.geofence.polygon.GmsPolygonPassiveMonitor
import io.customer.geofence.polygon.PolygonApproachMonitor
import io.customer.geofence.polygon.PolygonApproachWorkScheduler
import io.customer.geofence.polygon.PolygonBootSessionProvider
import io.customer.geofence.polygon.PolygonFreshFixSource
import io.customer.geofence.polygon.PolygonGeofenceServiceController
import io.customer.geofence.polygon.PolygonLocationEngine
import io.customer.geofence.polygon.PolygonPassiveMonitor
import io.customer.geofence.polygon.PolygonRecheckScheduler
import io.customer.geofence.polygon.PolygonSupport
import io.customer.geofence.polygon.WorkManagerPolygonRecheckScheduler
import io.customer.geofence.store.GeofenceCooldownStore
import io.customer.geofence.store.GeofenceCooldownStoreImpl
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.store.GeofenceRegionStoreImpl
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.geofence.worker.AsyncGeofenceEventTracker
import io.customer.geofence.worker.GeofenceEventScheduler
import io.customer.geofence.worker.GeofenceEventTracker
import io.customer.geofence.worker.GeofenceEventTrackerImpl
import io.customer.sdk.core.di.AndroidSDKComponent
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.clock
import io.customer.sdk.core.di.httpClient
import io.customer.sdk.core.di.workManagerProvider
import io.customer.sdk.data.store.PendingDeliveryFlusher
import io.customer.sdk.data.store.PendingDeliveryStore

internal val SDKComponent.geofenceLogger: GeofenceLogger
    get() = singleton { GeofenceLogger(logger) }

/**
 * Shared by the mapper and the ranker so they can't disagree. Both default to
 * [PolygonSupport.Disabled], so a seam that misses this wiring fails closed.
 */
internal val SDKComponent.polygonSupport: PolygonSupport
    get() = singleton { PolygonSupport.Enabled }

internal val AndroidSDKComponent.geofencingClient: GeofencingClient
    get() = newInstance { LocationServices.getGeofencingClient(applicationContext) }

internal val AndroidSDKComponent.polygonFusedLocationClient: FusedLocationProviderClient
    get() = singleton { LocationServices.getFusedLocationProviderClient(applicationContext) }

internal val AndroidSDKComponent.polygonApproachWorkScheduler: PolygonApproachWorkScheduler
    get() = singleton {
        PolygonApproachWorkScheduler(
            workManagerProvider = SDKComponent.workManagerProvider,
            store = geofenceRegionStore,
            bootSessionProvider = polygonBootSessionProvider
        )
    }

internal val AndroidSDKComponent.polygonRecheckScheduler: PolygonRecheckScheduler
    // Keyed by the interface type, so an override registered for the interface takes effect.
    get() = singleton<PolygonRecheckScheduler> {
        WorkManagerPolygonRecheckScheduler(workManagerProvider = SDKComponent.workManagerProvider)
    }

internal val AndroidSDKComponent.polygonPassiveMonitor: PolygonPassiveMonitor
    get() = singleton<PolygonPassiveMonitor> {
        GmsPolygonPassiveMonitor(
            context = applicationContext,
            client = polygonFusedLocationClient,
            logger = SDKComponent.geofenceLogger
        )
    }

internal val AndroidSDKComponent.polygonBootSessionProvider: PolygonBootSessionProvider
    get() = singleton<PolygonBootSessionProvider> { AndroidPolygonBootSessionProvider(applicationContext) }

internal val AndroidSDKComponent.geofenceReceiverToggle: GeofenceReceiverToggle
    get() = newInstance { GeofenceReceiverToggle(applicationContext) }

internal val AndroidSDKComponent.geofenceManager: GeofenceRegistrar
    get() = singleton<GeofenceRegistrar> {
        GeofenceManager(
            context = applicationContext,
            client = geofencingClient,
            receiverToggle = geofenceReceiverToggle,
            permissionChecker = geofencePermissionChecker,
            logger = SDKComponent.geofenceLogger
        )
    }

// Singleton: the pipeline's transition mutex must outlive a single broadcast delivery.
internal val AndroidSDKComponent.geofenceCrossingPipeline: GeofenceCrossingPipeline
    get() = singleton {
        GeofenceCrossingPipeline(
            regionStore = geofenceRegionStore,
            services = geofenceServices,
            registrar = geofenceManager,
            transitionProcessor = geofenceBusinessTransitionProcessor,
            dwellCoordinator = geofenceDwellCoordinator,
            polygonController = polygonGeofenceServiceController,
            logger = SDKComponent.geofenceLogger
        )
    }

internal val AndroidSDKComponent.geofenceEventTracker: GeofenceEventTracker
    get() = singleton<GeofenceEventTracker> {
        GeofenceEventTrackerImpl(SDKComponent.httpClient, geofenceRegionStore)
    }

// One instance so every writer and delivery channel shares one lock and file (at-least-once,
// deduped downstream by transitionId).
internal val AndroidSDKComponent.pendingGeofenceDeliveryStore: PendingDeliveryStore<PendingGeofenceDelivery>
    get() = singleton {
        PendingDeliveryStore(
            context = applicationContext,
            fileName = PendingGeofenceDelivery.FILE_NAME,
            elementSerializer = PendingGeofenceDelivery.serializer(),
            logger = SDKComponent.logger
        )
    }

internal val AndroidSDKComponent.geofenceDeliveryFlusher: PendingDeliveryFlusher<PendingGeofenceDelivery>
    get() = singleton {
        PendingDeliveryFlusher(
            store = pendingGeofenceDeliveryStore,
            workManagerProvider = SDKComponent.workManagerProvider,
            dispatchersProvider = SDKComponent.dispatchersProvider,
            // Null skips cancelling: the workers are one shared ordered chain, and cancelling it for
            // one row could strand a transition appended mid-flush. A worker whose row is gone no-ops.
            uniqueWorkName = { null },
            stopOnFailure = true
        )
    }

internal val AndroidSDKComponent.asyncGeofenceEventTracker: AsyncGeofenceEventTracker
    get() = singleton {
        AsyncGeofenceEventTracker(
            tracker = geofenceEventTracker,
            pendingStore = pendingGeofenceDeliveryStore,
            dispatcher = SDKComponent.dispatchersProvider,
            logger = SDKComponent.geofenceLogger
        )
    }

internal val AndroidSDKComponent.geofenceEventScheduler: GeofenceEventScheduler
    get() = singleton { GeofenceEventScheduler(SDKComponent.workManagerProvider, asyncGeofenceEventTracker) }

internal val AndroidSDKComponent.geofenceTransitionEmitter: GeofenceTransitionEmitter
    get() = singleton {
        GeofenceTransitionEmitter(
            cooldownFilter = geofenceCooldownFilter,
            pendingStore = pendingGeofenceDeliveryStore,
            scheduler = geofenceEventScheduler,
            regionStore = geofenceRegionStore,
            logger = SDKComponent.geofenceLogger
        )
    }

internal val AndroidSDKComponent.geofenceBusinessTransitionProcessor: GeofenceBusinessTransitionProcessor
    get() = singleton {
        GeofenceBusinessTransitionProcessor(
            store = geofenceRegionStore,
            secureUserStore = secureUserStore,
            transitionEmitter = geofenceTransitionEmitter,
            logger = SDKComponent.geofenceLogger
        )
    }

internal val AndroidSDKComponent.geofenceDwellCoordinator: GeofenceDwellCoordinator
    get() = singleton {
        GeofenceDwellCoordinator(
            store = geofenceRegionStore,
            transitionProcessor = geofenceBusinessTransitionProcessor
        )
    }

internal val AndroidSDKComponent.polygonLocationEngine: PolygonLocationEngine
    get() = singleton {
        PolygonLocationEngine(
            store = geofenceRegionStore,
            transitionProcessor = geofenceBusinessTransitionProcessor,
            dwellCoordinator = geofenceDwellCoordinator,
            clock = SDKComponent.clock,
            logger = SDKComponent.geofenceLogger
        )
    }

internal val AndroidSDKComponent.polygonApproachMonitor: PolygonApproachMonitor
    get() = singleton {
        PolygonApproachMonitor(
            context = applicationContext,
            client = polygonFusedLocationClient,
            store = geofenceRegionStore,
            logger = SDKComponent.geofenceLogger,
            backgroundContext = SDKComponent.dispatchersProvider.background
        )
    }

internal val AndroidSDKComponent.polygonFreshFixSource: PolygonFreshFixSource
    get() = singleton<PolygonFreshFixSource> {
        GmsPolygonFreshFixSource(client = polygonFusedLocationClient)
    }

internal val AndroidSDKComponent.polygonGeofenceServiceController: PolygonGeofenceServiceController
    get() = singleton {
        PolygonGeofenceServiceController(
            context = applicationContext,
            store = geofenceRegionStore,
            engine = polygonLocationEngine,
            approachMonitor = polygonApproachMonitor,
            manager = geofenceManager,
            secureUserStore = secureUserStore,
            freshFixSource = polygonFreshFixSource,
            recheckScheduler = polygonRecheckScheduler,
            passiveMonitor = polygonPassiveMonitor,
            logger = SDKComponent.geofenceLogger
        )
    }

internal val SDKComponent.geofenceDistanceFilter: GeofenceDistanceFilter
    get() = newInstance<GeofenceDistanceFilter> {
        GeofenceDistanceFilter(polygonSupport = polygonSupport)
    }

internal val SDKComponent.geofenceJsonSerializer: GeofenceJsonSerializer
    get() = singleton { GeofenceJsonSerializer() }

internal val SDKComponent.geofenceApiService: GeofenceApiService
    get() = newInstance<GeofenceApiService> {
        GeofenceApiServiceImpl(httpClient, geofenceJsonSerializer)
    }

internal val AndroidSDKComponent.geofenceCooldownStore: GeofenceCooldownStore
    get() = singleton<GeofenceCooldownStore> { GeofenceCooldownStoreImpl(applicationContext) }

internal val AndroidSDKComponent.geofenceCooldownFilter: GeofenceCooldownFilter
    get() = singleton<GeofenceCooldownFilter> {
        GeofenceCooldownFilter(geofenceCooldownStore, geofenceRegionStore, SDKComponent.clock)
    }

internal val AndroidSDKComponent.geofenceRegionStore: GeofenceRegionStore
    get() = singleton<GeofenceRegionStore> {
        GeofenceRegionStoreImpl(
            context = applicationContext,
            jsonSerializer = SDKComponent.geofenceJsonSerializer,
            logger = SDKComponent.logger
        )
    }

internal val AndroidSDKComponent.geofencePackageInfo: GeofencePackageInfo
    get() = newInstance { GeofencePackageInfo(applicationContext) }

internal val AndroidSDKComponent.geofenceRepository: GeofenceRepository
    get() = singleton<GeofenceRepository> {
        GeofenceRepositoryImpl(
            apiService = SDKComponent.geofenceApiService,
            store = geofenceRegionStore,
            distanceFilter = SDKComponent.geofenceDistanceFilter,
            manager = geofenceManager,
            secureUserStore = secureUserStore,
            cooldownFilter = geofenceCooldownFilter,
            transitionEmitter = geofenceTransitionEmitter,
            clock = SDKComponent.clock,
            packageInfo = geofencePackageInfo,
            logger = SDKComponent.geofenceLogger,
            polygonController = polygonGeofenceServiceController,
            dwellCoordinator = geofenceDwellCoordinator,
            polygonSupport = SDKComponent.polygonSupport
        )
    }

// Singleton: module init and foreground entry share its "already reported this tier" memory.
internal val AndroidSDKComponent.geofencePermissionReporter: GeofencePermissionReporter
    get() = singleton {
        GeofencePermissionReporter(
            permissionChecker = geofencePermissionChecker,
            logger = SDKComponent.geofenceLogger
        )
    }

internal val AndroidSDKComponent.geofencePermissionChecker: GeofencePermissionChecker
    get() = newInstance { GeofencePermissionChecker(applicationContext) }

internal val AndroidSDKComponent.geofenceServices: GeofenceServices
    get() = singleton<GeofenceServices> {
        GeofenceServicesImpl(
            repository = geofenceRepository,
            secureUserStore = secureUserStore,
            regionStore = geofenceRegionStore,
            cooldownFilter = geofenceCooldownFilter,
            polygonController = polygonGeofenceServiceController,
            scope = SDKComponent.scopeProvider.geofenceScope,
            logger = SDKComponent.geofenceLogger,
            permissionChecker = geofencePermissionChecker,
            clock = SDKComponent.clock
        )
    }
