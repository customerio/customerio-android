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
 * so the anonymous ID, user ID and queued events carry over instead of being reset. Finished batches
 * are rewritten to the new key, since the old key may be disabled before they upload.
 *
 * Must run before the Analytics instance for the new key is created.
 */
internal class AnalyticsStorageMigration(
    private val context: Context,
    private val logger: Logger
) {
    fun migrate(previousKey: String?, newKey: String) {
        // Only move storage when switching to a public key, legacy key changes behave as before
        if (!CioApiKey.isPublicKey(newKey)) return

        val pendingEventsKey = "$PENDING_EVENTS_PREFIX$newKey"
        runCatching {
            if (!previousKey.isNullOrEmpty() && previousKey != newKey && movePrefs(previousKey, newKey)) {
                migrationPrefs.edit().putString(pendingEventsKey, previousKey).commit()
                logger.debug("Moved analytics storage from previous key to new public key")
            }

            // Retried on every launch until all queued events have moved
            val pendingFromKey = migrationPrefs.getString(pendingEventsKey, null) ?: return
            if (moveEventFiles(pendingFromKey, newKey)) {
                migrationPrefs.edit().remove(pendingEventsKey).commit()
            } else {
                logger.error("Failed to move queued analytics events to new public key, will retry on next launch")
            }
        }.onFailure { ex ->
            logger.error("Failed to move analytics storage to new public key: ${ex.message}")
        }
    }

    /**
     * Returns true if prefs were moved. Never overwrites storage the new key already has.
     */
    private fun movePrefs(previousKey: String, newKey: String): Boolean {
        val previousPrefs = prefs(previousKey)
        val previousValues = previousPrefs.all
        if (previousValues.isEmpty()) return false

        val newPrefs = prefs(newKey)
        if (newPrefs.all.isNotEmpty()) return false

        val editor = newPrefs.edit()
        previousValues.forEach { (key, value) ->
            // Settings are fetched per key, so let the new key fetch its own
            if (key == KEY_SETTINGS) return@forEach
            val newPrefKey = if (key == fileIndexKey(previousKey)) fileIndexKey(newKey) else key
            editor.put(newPrefKey, value)
        }
        editor.commit()
        previousPrefs.edit().clear().commit()
        return true
    }

    /**
     * Returns false if any file couldn't be moved, so the move is retried later.
     */
    private fun moveEventFiles(previousKey: String, newKey: String): Boolean {
        val eventsDirectory = context.getDir(EVENTS_DIRECTORY, Context.MODE_PRIVATE)
        val previousPrefix = "$previousKey-"
        val files = eventsDirectory.listFiles()?.filter { file -> file.name.startsWith(previousPrefix) }.orEmpty()
        return files.all { file ->
            runCatching {
                rewriteBatchKey(file, previousKey, newKey)
                val newName = "$newKey-" + file.name.removePrefix(previousPrefix)
                file.renameTo(File(eventsDirectory, newName))
            }.getOrDefault(false)
        }
    }

    /**
     * Finished batches end with `"writeKey":"<key>"`, which the upload sends. Unfinished (`.tmp`)
     * ones get the new key when analytics finishes them.
     */
    private fun rewriteBatchKey(file: File, previousKey: String, newKey: String) {
        val previousField = "\"writeKey\":\"$previousKey\""
        val contents = file.readText()
        if (!contents.contains(previousField)) return
        file.writeText(contents.replace(previousField, "\"writeKey\":\"$newKey\""))
    }

    private val migrationPrefs: SharedPreferences
        get() = context.getSharedPreferences(MIGRATION_PREFS_NAME, Context.MODE_PRIVATE)

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

        const val MIGRATION_PREFS_NAME = "io.customer.sdk.analytics_storage_migration"

        // Previous key whose queued events still need moving to the new key
        const val PENDING_EVENTS_PREFIX = "pending_events_from."
    }
}
