package io.customer.location.geofence.di

import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.LocationServices
import io.customer.location.geofence.GeofenceDistanceFilter
import io.customer.location.geofence.GeofenceLogger
import io.customer.location.geofence.GeofenceManager
import io.customer.location.geofence.GeofenceReceiverToggle
import io.customer.location.geofence.worker.AsyncGeofenceEventTracker
import io.customer.location.geofence.worker.GeofenceEventScheduler
import io.customer.location.geofence.worker.GeofenceEventTracker
import io.customer.location.geofence.worker.GeofenceEventTrackerImpl
import io.customer.sdk.core.di.AndroidSDKComponent
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.httpClient
import io.customer.sdk.core.di.workManagerProvider

internal val SDKComponent.geofenceLogger: GeofenceLogger
    get() = singleton { GeofenceLogger(logger) }

internal val AndroidSDKComponent.geofencingClient: GeofencingClient
    get() = newInstance { LocationServices.getGeofencingClient(applicationContext) }

internal val AndroidSDKComponent.geofenceReceiverToggle: GeofenceReceiverToggle
    get() = newInstance { GeofenceReceiverToggle(applicationContext) }

internal val AndroidSDKComponent.geofenceManager: GeofenceManager
    get() = singleton {
        GeofenceManager(applicationContext, geofencingClient, geofenceReceiverToggle, SDKComponent.geofenceLogger)
    }

internal val AndroidSDKComponent.geofenceEventTracker: GeofenceEventTracker
    get() = singleton<GeofenceEventTracker> {
        GeofenceEventTrackerImpl(SDKComponent.httpClient, secureUserStore, SDKComponent.geofenceLogger)
    }

internal val AndroidSDKComponent.asyncGeofenceEventTracker: AsyncGeofenceEventTracker
    get() = singleton { AsyncGeofenceEventTracker(geofenceEventTracker, SDKComponent.dispatchersProvider) }

internal val AndroidSDKComponent.geofenceEventScheduler: GeofenceEventScheduler
    get() = singleton { GeofenceEventScheduler(SDKComponent.workManagerProvider, asyncGeofenceEventTracker) }

internal val SDKComponent.geofenceDistanceFilter: GeofenceDistanceFilter
    get() = newInstance<GeofenceDistanceFilter> { GeofenceDistanceFilter() }
