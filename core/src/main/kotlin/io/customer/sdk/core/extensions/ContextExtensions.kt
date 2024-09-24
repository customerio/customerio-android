package io.customer.sdk.core.extensions

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import io.customer.sdk.core.di.SDKComponent

/**
 * Retrieves application info from the package manager for given package name.
 *
 * @throws PackageManager.NameNotFoundException If the package name is not found.
 * @return The application info for the given package name.
 */
@Throws
private fun PackageManager.packageApplicationInfo(packageName: String): ApplicationInfo {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getApplicationInfo(
            packageName,
            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
        )
    } else {
        getApplicationInfo(
            packageName,
            PackageManager.GET_META_DATA
        )
    }
}

/**
 * Retrieves application meta-data from AndroidManifest.xml file.
 *
 * @return The meta-data bundle from application info.
 */
fun Context.applicationMetaData(): Bundle? {
    var applicationPackageName = ""
    return runCatching {
        applicationPackageName = packageName
        return@runCatching packageManager.packageApplicationInfo(applicationPackageName)
    }.onFailure { ex ->
        SDKComponent.logger.error(
            "Failed to get ApplicationInfo for package: $applicationPackageName with error: ${ex.message}"
        )
    }.getOrNull()?.metaData
}
