package io.customer.messagingpush.store

import android.content.Context
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat

/**
 * Remembers the app's `autoTrackPushEvents` setting from its last SDK setup, so a push that arrives
 * before the app sets up the SDK can follow it. The saved value belongs to the installed app version:
 * after an install or update it reads as unknown until the new version sets up the SDK, because that
 * version may have turned tracking off.
 *
 * @param installedAppVersion identifies the installed app; see [installedAppVersion].
 */
internal class PushSettingsStore(
    context: Context,
    private val installedAppVersion: () -> String?
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    /** The setting saved by this installed app version, or null when it hasn't saved one. */
    fun autoTrackPushEvents(): Boolean? {
        val appVersion = installedAppVersion() ?: return null
        if (prefs.getString(KEY_APP_VERSION, null) != appVersion || !prefs.contains(KEY_AUTO_TRACK_PUSH_EVENTS)) return null
        return prefs.getBoolean(KEY_AUTO_TRACK_PUSH_EVENTS, true)
    }

    fun saveAutoTrackPushEvents(enabled: Boolean) {
        val appVersion = installedAppVersion() ?: return
        prefs.edit {
            putString(KEY_APP_VERSION, appVersion)
            putBoolean(KEY_AUTO_TRACK_PUSH_EVENTS, enabled)
        }
    }

    companion object {
        private const val PREFS_NAME = "io.customer.messagingpush.settings"
        private const val KEY_APP_VERSION = "app_version"
        private const val KEY_AUTO_TRACK_PUSH_EVENTS = "auto_track_push_events"

        /** Changes with every install or update of the app, including a reinstall of the same version. */
        fun installedAppVersion(context: Context): String? = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${PackageInfoCompat.getLongVersionCode(info)}:${info.lastUpdateTime}"
        }.getOrNull()
    }
}
