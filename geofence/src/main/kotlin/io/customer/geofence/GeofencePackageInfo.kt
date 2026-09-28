package io.customer.geofence

import android.content.Context

/**
 * An app update can wipe GMS registrations like a reboot, so a change since the last registration
 * means OS state can't be trusted.
 */
internal class GeofencePackageInfo(private val context: Context) {
    fun lastUpdateTimeMs(): Long? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrNull()
}
