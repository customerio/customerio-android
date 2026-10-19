package io.customer.datapipelines.migration

import android.content.Context
import android.content.SharedPreferences
import com.segment.analytics.kotlin.core.Storage
import io.customer.sdk.core.util.CioApiKey
import io.customer.sdk.core.util.Logger
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Segment keeps analytics storage per write key: identity in the `analytics-android-<key>` prefs and
 * queued events in `<key>-<index>` files in the shared `segment-disk-queue` folder. When an app
 * switches to a public (`pk_`) key, this keeps the user's identity and their unsent events.
 *
 * Finished batches need nothing: analytics uploads every finished file in the folder, and each one
 * carries the key it was written with. Only the previous key's unfinished batch is moved, since
 * analytics only ever finishes the current key's batch.
 */
internal class AnalyticsStorageMigration(
    private val context: Context,
    private val logger: Logger
) {
    /**
     * Copies the previous key's identity to the new key, keeping it for the previous key too.
     * Must run before the Analytics instance for the new key is created, as it reads identity then.
     */
    fun copyIdentity(previousKey: String?, newKey: String) {
        if (!shouldMigrate(previousKey, newKey)) return

        runCatching {
            val newPrefs = prefs(newKey)
            // Never replace an identity the new key already has
            if (IDENTITY_KEYS.any { newPrefs.contains(it) }) return

            val previousPrefs = prefs(previousKey)
            val editor = newPrefs.edit()
            IDENTITY_KEYS.forEach { key -> previousPrefs.getString(key, null)?.let { editor.putString(key, it) } }
            editor.commit()
        }.onFailure { ex ->
            logger.error("Failed to copy analytics identity to new public key: ${ex.message}")
        }
    }

    /**
     * Adds the events from other keys' unfinished batches to the new key's queue as they are, keeping
     * their messageId, timestamp and userId, then deletes those batches. Includes batches left by an
     * earlier key change that didn't finish. Runs on [dispatcher].
     */
    fun moveUnfinishedEvents(
        previousKey: String?,
        newKey: String,
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        storage: Storage
    ) {
        if (!shouldMigrate(previousKey, newKey)) return

        scope.launch(dispatcher) {
            runCatching {
                val eventsDirectory = context.getDir(EVENTS_DIRECTORY, Context.MODE_PRIVATE)
                // Analytics only finishes the current key's batch, so any other key's is left over
                val unfinishedBatches = eventsDirectory.listFiles()
                    ?.filter { file -> !file.name.startsWith("$newKey-") && file.name.endsWith(TMP_SUFFIX) }
                    .orEmpty()
                unfinishedBatches.forEach { file ->
                    // Unknown formats are kept rather than deleted without moving their events
                    val events = completeEvents(file) ?: return@forEach
                    events.forEach { event -> storage.write(Storage.Constants.Events, event) }
                    file.delete()
                }
            }.onFailure { ex ->
                logger.error("Failed to move queued analytics events to new public key: ${ex.message}")
            }
        }
    }

    // Only when switching to a public key, legacy key changes behave as before
    private fun shouldMigrate(previousKey: String?, newKey: String): Boolean =
        CioApiKey.isPublicKey(newKey) && !previousKey.isNullOrEmpty() && previousKey != newKey

    /**
     * An unfinished batch is `{"batch":[` then events split by commas, with no closing yet. Returns
     * each complete event as written, dropping a last event cut off part way, or null for any other format.
     */
    private fun completeEvents(file: File): List<String>? {
        val contents = file.readText()
        if (!contents.startsWith(BATCH_START)) return null

        val events = mutableListOf<String>()
        var depth = 0
        var inString = false
        var escaped = false
        var eventStart = -1
        for (index in BATCH_START.length until contents.length) {
            val char = contents[index]
            when {
                escaped -> escaped = false
                inString && char == '\\' -> escaped = true
                char == '"' -> inString = !inString
                inString -> Unit
                char == '{' -> {
                    if (depth == 0) eventStart = index
                    depth++
                }
                char == '}' -> {
                    depth--
                    if (depth == 0) events.add(contents.substring(eventStart, index + 1))
                }
            }
        }
        return events
    }

    private fun prefs(key: String?): SharedPreferences =
        context.getSharedPreferences("$PREFS_PREFIX$key", Context.MODE_PRIVATE)

    internal companion object {
        // Names used by Segment's AndroidStorageProvider
        const val PREFS_PREFIX = "analytics-android-"
        const val EVENTS_DIRECTORY = "segment-disk-queue"
        private const val TMP_SUFFIX = ".tmp"
        private const val BATCH_START = "{\"batch\":["

        private val IDENTITY_KEYS = listOf(
            Storage.Constants.AnonymousId.rawVal,
            Storage.Constants.UserId.rawVal,
            Storage.Constants.Traits.rawVal
        )
    }
}
