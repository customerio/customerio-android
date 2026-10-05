package io.customer.datapipelines.migration

import android.content.Context
import android.content.SharedPreferences
import io.customer.sdk.core.util.CioApiKey
import io.customer.sdk.core.util.Logger
import java.io.File

/**
 * Segment keeps analytics storage per write key: the `analytics-android-<key>` prefs (anonymous ID,
 * user ID, traits, event file index) and the `<key>-<index>` event files in `segment-disk-queue`.
 * When an app switches to a public (`wk_`) key, this moves that storage over from the previous key
 * so the anonymous ID, user ID and queued events carry over instead of being reset.
 *
 * Must run before the Analytics instance for the new key is created.
 */
internal class AnalyticsStorageMigration(
    private val context: Context,
    private val logger: Logger
) {
    fun migrate(previousKey: String?, newKey: String) {
        // Only move storage when switching to a public key, legacy key changes behave as before
        if (previousKey.isNullOrEmpty() || previousKey == newKey || !CioApiKey.isPublicKey(newKey)) return

        val previousPrefs = prefs(previousKey)
        val previousValues = previousPrefs.all
        if (previousValues.isEmpty()) return

        val newPrefs = prefs(newKey)
        // Never overwrite storage the new key already has
        if (newPrefs.all.isNotEmpty()) return

        runCatching {
            val editor = newPrefs.edit()
            previousValues.forEach { (key, value) ->
                // Settings are fetched per key, so let the new key fetch its own
                if (key == KEY_SETTINGS) return@forEach
                val newPrefKey = if (key == fileIndexKey(previousKey)) fileIndexKey(newKey) else key
                editor.put(newPrefKey, value)
            }
            editor.commit()

            moveEventFiles(previousKey, newKey)

            previousPrefs.edit().clear().commit()
            logger.debug("Moved analytics storage from previous key to new public key")
        }.onFailure { ex ->
            logger.error("Failed to move analytics storage to new public key: ${ex.message}")
        }
    }

    private fun moveEventFiles(previousKey: String, newKey: String) {
        val eventsDirectory = context.getDir(EVENTS_DIRECTORY, Context.MODE_PRIVATE)
        val previousPrefix = "$previousKey-"
        eventsDirectory.listFiles()
            ?.filter { file -> file.name.startsWith(previousPrefix) }
            ?.forEach { file ->
                val newName = "$newKey-" + file.name.removePrefix(previousPrefix)
                file.renameTo(File(eventsDirectory, newName))
            }
    }

    private fun prefs(key: String): SharedPreferences =
        context.getSharedPreferences("$PREFS_PREFIX$key", Context.MODE_PRIVATE)

    private fun fileIndexKey(key: String): String = "$FILE_INDEX_KEY_PREFIX$key"

    @Suppress("UNCHECKED_CAST")
    private fun SharedPreferences.Editor.put(key: String, value: Any?) {
        when (value) {
            is String -> putString(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Boolean -> putBoolean(key, value)
            is Set<*> -> putStringSet(key, value as Set<String>)
        }
    }

    internal companion object {
        // Names used by Segment's AndroidStorageProvider
        const val PREFS_PREFIX = "analytics-android-"
        const val EVENTS_DIRECTORY = "segment-disk-queue"
        const val FILE_INDEX_KEY_PREFIX = "segment.events.file.index."
        const val KEY_SETTINGS = "segment.settings"
    }
}
