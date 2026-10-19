package io.customer.datapipelines.migration

import android.content.Context
import android.content.SharedPreferences
import io.customer.sdk.core.util.CioApiKey
import io.customer.sdk.core.util.Logger
import java.io.File
import java.io.IOException

/**
 * Segment keeps analytics storage per write key: the `analytics-android-<key>` prefs (anonymous ID,
 * user ID, traits, event file index) and the `<key>-<index>` event files in `segment-disk-queue`.
 * When an app switches to a public (`pk_`) key, this moves that storage over from the previous key
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

        runCatching {
            var movedFromKey: String? = null
            if (!previousKey.isNullOrEmpty() && previousKey != newKey && movePrefs(previousKey, newKey)) {
                movedFromKey = previousKey
                logger.debug("Moved analytics storage from previous key to new public key")
            }

            // Retried on every launch until all queued events have moved. Moves left from an earlier
            // key change are included, so their events follow the newest key.
            val pendingFromKeys = migrationPrefs.all.keys
                .filter { it.startsWith(PENDING_EVENTS_PREFIX) }
                .map { it.removePrefix(PENDING_EVENTS_PREFIX) }
                .plus(listOfNotNull(movedFromKey))
                .distinct()
            pendingFromKeys.forEach { fromKey ->
                if (fromKey == newKey) {
                    migrationPrefs.edit().remove(pendingEventsKey(fromKey)).commit()
                    return@forEach
                }
                // A copy saved this launch is only in memory, so only retry copies from earlier launches
                if (fromKey != movedFromKey && !isPrefsSaved(fromKey)) retryPrefsSave(fromKey, newKey)
                if (moveEventFiles(fromKey, newKey) && isPrefsSaved(fromKey)) {
                    migrationPrefs.edit().remove(pendingEventsKey(fromKey)).commit()
                } else {
                    logger.error("Failed to move queued analytics events to new public key, will retry on next launch")
                }
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
        // Mark the move pending before touching prefs, so a crash part way still finishes it next launch
        setPrefsSaved(previousKey, saved = false)
        // Previous prefs are kept until the copy is saved, a failed save is retried next launch
        if (editor.commit()) clearMovedPrefs(previousKey)
        return true
    }

    /**
     * A copy that failed to save may since have been saved with analytics' own writes, so finish the
     * move if the new key has prefs now, or copy again if it doesn't.
     */
    private fun retryPrefsSave(fromKey: String, newKey: String) {
        if (prefs(newKey).all.isNotEmpty() || prefs(fromKey).all.isEmpty()) {
            clearMovedPrefs(fromKey)
        } else {
            movePrefs(fromKey, newKey)
        }
    }

    private fun clearMovedPrefs(previousKey: String) {
        if (prefs(previousKey).edit().clear().commit()) setPrefsSaved(previousKey, saved = true)
    }

    private fun isPrefsSaved(fromKey: String): Boolean = migrationPrefs.getBoolean(pendingEventsKey(fromKey), true)

    private fun setPrefsSaved(fromKey: String, saved: Boolean) {
        migrationPrefs.edit().putBoolean(pendingEventsKey(fromKey), saved).commit()
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
                rewriteBatchKey(file, newKey)
                val target = freeTarget(eventsDirectory, newKey, file.name.removePrefix(previousPrefix))
                target != null && file.renameTo(target)
            }.getOrDefault(false)
        }
    }

    /**
     * Keeps the batch's own number if the new key hasn't used it, or else takes the next free one,
     * so a moved batch never replaces one the new key already wrote.
     */
    private fun freeTarget(eventsDirectory: File, newKey: String, batchName: String): File? {
        val isUnfinished = batchName.endsWith(TMP_SUFFIX)
        var index = batchName.removeSuffix(TMP_SUFFIX).toIntOrNull()
            ?: return File(eventsDirectory, "$newKey-$batchName").takeIf { !it.exists() }
        while (true) {
            val name = "$newKey-$index"
            if (!File(eventsDirectory, name).exists() && !File(eventsDirectory, "$name$TMP_SUFFIX").exists()) {
                return File(eventsDirectory, if (isUnfinished) "$name$TMP_SUFFIX" else name)
            }
            index++
        }
    }

    /**
     * Finished batches end with `"writeKey":"<key>"}`, which the upload sends. Only that trailing field
     * is rewritten, so event data is never changed. Unfinished (`.tmp`) ones have no batch key yet and
     * get the new key when analytics finishes them.
     */
    private fun rewriteBatchKey(file: File, newKey: String) {
        if (file.name.endsWith(TMP_SUFFIX)) return
        val contents = file.readText()
        val batchKeyField = BATCH_WRITE_KEY_REGEX.find(contents) ?: return
        val newField = "\"writeKey\":\"$newKey\"}"
        if (batchKeyField.value == newField) return
        // Written beside the batch then renamed over it, so a failed write never leaves a partial batch.
        // It ends in .tmp so analytics never uploads a leftover one.
        val rewritten = File(file.parentFile, REWRITE_FILE_NAME)
        rewritten.writeText(contents.replaceRange(batchKeyField.range, newField))
        if (!rewritten.renameTo(file)) throw IOException("Failed to replace batch ${file.name}")
    }

    private val migrationPrefs: SharedPreferences
        get() = context.getSharedPreferences(MIGRATION_PREFS_NAME, Context.MODE_PRIVATE)

    private fun prefs(key: String): SharedPreferences =
        context.getSharedPreferences("$PREFS_PREFIX$key", Context.MODE_PRIVATE)

    private fun fileIndexKey(key: String): String = "$FILE_INDEX_KEY_PREFIX$key"

    private fun pendingEventsKey(fromKey: String): String = "$PENDING_EVENTS_PREFIX$fromKey"

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

        // Marks a previous key whose storage still needs moving to the current key, true once its prefs are saved
        const val PENDING_EVENTS_PREFIX = "pending_events_from."

        private const val TMP_SUFFIX = ".tmp"
        internal const val REWRITE_FILE_NAME = "cio-batch-rewrite.tmp"

        // The batch's own key, last field of a finished batch file
        private val BATCH_WRITE_KEY_REGEX = Regex("\"writeKey\":\"[^\"]*\"\\}\\s*$")
    }
}
