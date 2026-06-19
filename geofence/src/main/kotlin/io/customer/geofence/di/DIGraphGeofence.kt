package io.customer.geofence.di

import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.LocationServices
import io.customer.geofence.GeofenceCooldownFilter
import io.customer.geofence.GeofenceDistanceFilter
import io.customer.geofence.GeofenceJsonSerializer
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.GeofenceManager
import io.customer.geofence.GeofencePermissionChecker
import io.customer.geofence.GeofenceReceiverToggle
import io.customer.geofence.GeofenceRepository
import io.customer.geofence.GeofenceRepositoryImpl
import io.customer.geofence.GeofenceServices
import io.customer.geofence.GeofenceServicesImpl
import io.customer.geofence.GeofenceSyncMode
import io.customer.geofence.api.GeofenceApiService
import io.customer.geofence.api.GeofenceApiServiceImpl
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

internal val AndroidSDKComponent.geofencingClient: GeofencingClient
    get() = newInstance { LocationServices.getGeofencingClient(applicationContext) }

internal val AndroidSDKComponent.geofenceReceiverToggle: GeofenceReceiverToggle
    get() = newInstance { GeofenceReceiverToggle(applicationContext) }

internal val AndroidSDKComponent.geofenceManager: GeofenceManager
    get() = singleton {
        GeofenceManager(
            context = applicationContext,
            client = geofencingClient,
            receiverToggle = geofenceReceiverToggle,
            permissionChecker = geofencePermissionChecker,
            logger = SDKComponent.geofenceLogger
        )
    }

internal val AndroidSDKComponent.geofenceEventTracker: GeofenceEventTracker
    get() = singleton<GeofenceEventTracker> {
        GeofenceEventTrackerImpl(SDKComponent.httpClient)
    }

// Shared by the WorkManager worker, the async fallback, and the foreground flush
// so all three coordinate exactly-once delivery over one lock/file.
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
            dispatchersProvider = SDKComponent.dispatchersProvider
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

internal val SDKComponent.geofenceDistanceFilter: GeofenceDistanceFilter
    get() = newInstance<GeofenceDistanceFilter> { GeofenceDistanceFilter() }

internal val SDKComponent.geofenceJsonSerializer: GeofenceJsonSerializer
    get() = singleton { GeofenceJsonSerializer() }

internal val SDKComponent.geofenceApiService: GeofenceApiService
    get() = newInstance<GeofenceApiService> { GeofenceApiServiceImpl(httpClient, geofenceJsonSerializer) }

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

internal val AndroidSDKComponent.geofenceRepository: GeofenceRepository
    get() = singleton<GeofenceRepository> {
        GeofenceRepositoryImpl(
            apiService = SDKComponent.geofenceApiService,
            store = geofenceRegionStore,
            distanceFilter = SDKComponent.geofenceDistanceFilter,
            manager = geofenceManager,
            secureUserStore = secureUserStore,
            clock = SDKComponent.clock,
            logger = SDKComponent.geofenceLogger,
            syncMode = GeofenceSyncMode.active
        )
    }

internal val AndroidSDKComponent.geofencePermissionChecker: GeofencePermissionChecker
    get() = newInstance { GeofencePermissionChecker(applicationContext) }

internal val AndroidSDKComponent.geofenceServices: GeofenceServices
    get() = singleton<GeofenceServices> {
        GeofenceServicesImpl(
            repository = geofenceRepository,
            secureUserStore = secureUserStore,
            scope = SDKComponent.scopeProvider.geofenceScope,
            logger = SDKComponent.geofenceLogger,
            permissionChecker = geofencePermissionChecker
        )
    }
