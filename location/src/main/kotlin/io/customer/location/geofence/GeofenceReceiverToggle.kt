package io.customer.location.geofence

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Enables/disables geofence broadcast receivers via PackageManager. */
internal interface GeofenceReceiverToggle {
    fun setEnabled(enabled: Boolean)
}

internal class GeofenceReceiverToggleImpl(
    private val context: Context
) : GeofenceReceiverToggle {

    override fun setEnabled(enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val pm = context.packageManager
        listOf(GeofenceBroadcastReceiver::class.java, GeofenceBootReceiver::class.java).forEach {
            pm.setComponentEnabledSetting(
                ComponentName(context, it),
                state,
                PackageManager.DONT_KILL_APP
            )
        }
    }
}
