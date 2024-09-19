package io.customer.sdk.core.extensions

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import io.customer.sdk.core.di.SDKComponent

/**
 * Gets meta-data from AndroidManifest.xml file.
 *
 * @return The meta-data bundle from application info.
 */
fun Context.applicationMetaData(): Bundle? = runCatching {
    val appInfo: ApplicationInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getApplicationInfo(
            packageName,
            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
    }

    return@runCatching appInfo.metaData
}.onFailure { ex ->
    SDKComponent.logger.error("Failed to get application meta-data: ${ex.message}")
}.getOrNull()
