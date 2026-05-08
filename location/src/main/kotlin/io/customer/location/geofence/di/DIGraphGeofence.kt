package io.customer.location.geofence.di

import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.LocationServices
import io.customer.location.geofence.GeofenceLogger
import io.customer.location.geofence.GeofenceManager
import io.customer.location.geofence.GeofenceReceiverToggle
import io.customer.sdk.core.di.AndroidSDKComponent
import io.customer.sdk.core.di.SDKComponent

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
