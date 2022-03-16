package io.customer.sdk.data.store

import android.content.Context
import androidx.core.app.NotificationManagerCompat

interface ApplicationStore {
    // Customer App information
    val customerAppName: String
    val customerAppVersion: String

    val isPushSubscribed: Boolean
}

internal class ApplicationStoreImp(val context: Context) : ApplicationStore {

    private val appInfo: Pair<String, String> = getAppInformation()

    override val customerAppName: String
        get() = appInfo.first
    override val customerAppVersion: String
        get() = appInfo.second
    override val isPushSubscribed: Boolean
        get() = NotificationManagerCompat.from(context).areNotificationsEnabled()

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
