package io.customer.geofence.polygon

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build

internal class PolygonContinuousModeValidator(
    private val context: Context
) {
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
            return "continuous polygon tracking requires PolygonLocationService in the host manifest"
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
