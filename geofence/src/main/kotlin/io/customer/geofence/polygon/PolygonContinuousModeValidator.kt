package io.customer.geofence.polygon

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build

/**
 * Decides whether continuous mode may attempt a foreground-service start at all.
 *
 * The SDK's own manifest declares [PolygonLocationService] with `foregroundServiceType=location`,
 * so a missing declaration means a host removed or overrode it in the merged manifest. The two
 * foreground-service permissions are deliberately the host's to declare, and Android 14+ throws at
 * `startForeground` without them — checking here turns that crash-shaped failure into one log line
 * and an unchanged responsive session.
 */
internal class PolygonContinuousModeValidator(
    private val context: Context
) {
    /** Returns `null` when a start may be attempted, otherwise what the host still has to do. */
    fun configurationError(): String? {
        val serviceInfo = runCatching {
            val component = ComponentName(context, PolygonLocationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getServiceInfo(
                    component,
                    PackageManager.ComponentInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getServiceInfo(component, 0)
            }
        }.getOrNull()
        if (serviceInfo == null) {
            return "the SDK's PolygonLocationService is missing from the merged manifest"
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            serviceInfo.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION == 0
        ) {
            return "continuous polygon tracking requires foregroundServiceType=location"
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            !hasPermission(Manifest.permission.FOREGROUND_SERVICE)
        ) {
            return "continuous polygon tracking requires android.permission.FOREGROUND_SERVICE"
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !hasPermission(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        ) {
            return "continuous polygon tracking requires android.permission.FOREGROUND_SERVICE_LOCATION"
        }
        return null
    }

    private fun hasPermission(permission: String): Boolean =
        context.packageManager.checkPermission(permission, context.packageName) ==
            PackageManager.PERMISSION_GRANTED
}
