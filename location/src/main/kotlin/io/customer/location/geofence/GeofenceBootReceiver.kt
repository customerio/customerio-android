package io.customer.location.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.customer.location.geofence.di.geofenceLogger
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent

/** Re-registers persisted geofences after device reboot. */
class GeofenceBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        SDKComponent.setupAndroidComponent(context = context)
        val logger = SDKComponent.geofenceLogger

        logger.logReceiverSkipped("restore not yet wired")
    }
}
