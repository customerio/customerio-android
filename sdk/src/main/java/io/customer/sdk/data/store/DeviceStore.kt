package io.customer.sdk.data.store

import android.content.Context
import android.os.Build
import io.customer.sdk.Version

interface DeviceStore {

    // Brand: Google
    val deviceBrand: String

    // Device model: Pixel
    val deviceModel: String

    // Manufacturer: Samsung
    val deviceManufacturer: String

    // Android SDK Version: 21
    val deviceOSVersion: Int

    // Customer App information
    val customerAppName: String
    val customerAppVersion: String

    // SDK version
    val customerIOVersion: String

    fun buildUserAgent(): String
}

internal class DeviceStoreImp(val context: Context) : DeviceStore {

    private val appInfo: Pair<String, String> = getAppInformation()

    override val deviceBrand: String
        get() = Build.BRAND
    override val deviceModel: String
        get() = Build.MODEL
    override val deviceManufacturer: String
        get() = Build.MANUFACTURER
    override val deviceOSVersion: Int
        get() = Build.VERSION.SDK_INT
    override val customerAppName: String
        get() = appInfo.first
    override val customerAppVersion: String
        get() = appInfo.second
    override val customerIOVersion: String
        get() = Version.version

    override fun buildUserAgent(): String {
        return buildString {
            append("Customer.io Android Client/")
            append(customerIOVersion)
            append(" ($deviceManufacturer $deviceModel; $deviceOSVersion)")
            append(" $customerAppName/$customerAppVersion")
        }
    }

    private fun getAppInformation(): Pair<String, String> {
        val appName: String = try {
            context.applicationInfo.loadLabel(context.packageManager).toString()
        } catch (e: Exception) {
            "Unknown Name"
        }
        val appVersion: String = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "Unknown version"
        }
        return appName to appVersion
    }
}
