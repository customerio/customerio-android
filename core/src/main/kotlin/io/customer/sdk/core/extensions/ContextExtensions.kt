package io.customer.sdk.core.extensions

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import io.customer.sdk.core.di.SDKComponent

/**
 * Retrieves application meta-data from AndroidManifest.xml file.
 *
 * @return The meta-data bundle from application info.
 */
fun Context.applicationMetaData(): Bundle? = try {
    val applicationInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getApplicationInfo(
            packageName,
            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.getApplicationInfo(
            packageName,
            PackageManager.GET_META_DATA
        )
    }
    applicationInfo.metaData
} catch (ex: Exception) {
    SDKComponent.logger.error("Failed to get ApplicationInfo with error: ${ex.message}")
    null
}
