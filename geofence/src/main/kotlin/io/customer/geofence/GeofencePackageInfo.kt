package io.customer.geofence

import android.content.Context

/**
 * Reads the host package's last-update timestamp. An app update can wipe GMS
 * geofence registrations (like a reboot does), so a change in this value since
 * the last registration means OS state can't be trusted.
 */
internal class GeofencePackageInfo(private val context: Context) {
    fun lastUpdateTimeMs(): Long? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrNull()
}
