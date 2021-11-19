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

    /**
     * buildUserAgent - To get `user-agent` header value. This value depends on SDK version
     * and device detail such as OS version, device model, customer's app name etc
     *
     * If the device and OS information is available, it will return in following format :
     * `Customer.io Android Client/1.0.0-alpha.6 (Google Pixel 6; 30) User App/1.0`
     *
     * Otherwise will return
     * `Customer.io Android Client/1.0.0-alpha.6`
     */
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
            ""
        }
        val appVersion: String = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            ""
        }
        return appName to appVersion
    }
}
