package io.customer.sdk.data.store

import android.content.Context
import androidx.core.app.NotificationManagerCompat

interface ApplicationStore {
    // Customer App information

    val customerAppName: String?
    val customerAppVersion: String?
    val customerPackageName: String

    val isPushEnabled: Boolean
}

internal class ApplicationStoreImp(val context: Context) : ApplicationStore {

    override val customerAppName: String?
        get() = tryGetValueOrNull {
            context.applicationInfo.loadLabel(context.packageManager).toString()
        }
    override val customerAppVersion: String?
        get() = tryGetValueOrNull {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }
    override val customerPackageName: String
        get() = context.packageName
    override val isPushEnabled: Boolean
        get() = NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun tryGetValueOrNull(tryGetValue: () -> String): String? {
        return try {
            tryGetValue()
        } catch (e: Exception) {
            null
        }
    }
}
