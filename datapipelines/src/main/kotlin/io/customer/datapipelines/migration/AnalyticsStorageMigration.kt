package io.customer.datapipelines.migration

import android.content.Context
import android.content.SharedPreferences
import com.segment.analytics.kotlin.core.Storage
import io.customer.datapipelines.plugins.CUSTOMER_IO_DATA_PIPELINES
import io.customer.sdk.core.util.CioApiKey
import io.customer.sdk.core.util.Logger
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Segment keeps analytics storage per write key: identity in the `analytics-android-<key>` prefs and
 * queued events in `<key>-<index>` files in the shared `segment-disk-queue` folder. When an app
 * switches to a public (`pk_`) key, this keeps the user's identity and their unsent events.
 *
 * Finished batches need nothing: analytics uploads every finished file in the folder, and each one
 * carries the key it was written with. Only unfinished batches of this SDK's earlier keys are moved,
 * since analytics only ever finishes the current key's batch.
 */
internal class AnalyticsStorageMigration(
    private val context: Context,
    private val logger: Logger
) {
    /**
     * Returns the key the SDK ran with before [newKey]. Uses [savedKey] when there is one. SDK versions
     * before 4.5.3 didn't save their key, so on a switch to a public key this looks through the keys
     * with analytics storage instead. Keys whose saved analytics settings name the Customer.io
     * destination are this SDK's, and the most recently used one is taken. Segment's own SDK, which
     * an app can run too, never names ours there, so its storage is never taken. If
     * none name ours, a single other key is taken, and several are left alone.
     */
    fun previousKey(savedKey: String?, newKey: String): String? {
        if (!savedKey.isNullOrEmpty() || !CioApiKey.isPublicKey(newKey)) return savedKey

        return runCatching {
            val prefsFiles = File(context.applicationInfo.dataDir, PREFS_DIRECTORY).listFiles()
                ?.filter { file -> file.name.startsWith(PREFS_PREFIX) && file.name.endsWith(PREFS_SUFFIX) }
                .orEmpty()
            val keysByLastUse = prefsFiles
                .sortedByDescending { file -> file.lastModified() }
                .map { file -> file.name.removePrefix(PREFS_PREFIX).removeSuffix(PREFS_SUFFIX) }
                .filter { key -> key.isNotEmpty() && key != newKey }
            val keysBySettings = keysByLastUse.groupBy { key -> savedSettingsOwner(key) }
            val ownKeys = keysBySettings[SettingsOwner.CUSTOMER_IO].orEmpty()
            val unknownKeys = keysBySettings[SettingsOwner.UNKNOWN].orEmpty()
            when {
                ownKeys.isNotEmpty() -> ownKeys.first()
                unknownKeys.size == 1 -> unknownKeys.first()
                else -> {
                    if (keysByLastUse.isNotEmpty()) {
                        logger.info("Found ${keysByLastUse.size} earlier analytics storages and none known to be this SDK's, so none were moved to the new public key")
                    }
                    null
                }
            }
        }.onFailure { ex ->
            logger.error("Failed to find previous analytics key: ${ex.message}")
        }.getOrNull()
    }

    // Analytics saves the settings it has for each key, and this SDK always lists its destination there
    private fun savedSettingsOwner(key: String): SettingsOwner {
        val settings = prefs(key).getString(Storage.Constants.Settings.rawVal, null) ?: return SettingsOwner.UNKNOWN
        val integrations = runCatching { Json.parseToJsonElement(settings).jsonObject[INTEGRATIONS]?.jsonObject }
            .getOrNull() ?: return SettingsOwner.UNKNOWN
        return if (integrations.containsKey(CUSTOMER_IO_DATA_PIPELINES)) SettingsOwner.CUSTOMER_IO else SettingsOwner.OTHER
    }

    private enum class SettingsOwner { CUSTOMER_IO, OTHER, UNKNOWN }

    /**
     * Copies the previous key's identity, app version and build, and device id to the new key, keeping
     * them for the previous key too. Analytics compares the saved build to tell "Application Installed"
     * from "Application Updated", so without it every switching user would look newly installed.
     * Must run before the Analytics instance for the new key is created, as it reads these then.
     */
    fun copyIdentity(previousKey: String?, newKey: String) {
        if (!shouldMigrate(previousKey, newKey)) return

        runCatching {
            val newPrefs = prefs(newKey)
            // Never replace values the new key already has
            if (COPIED_KEYS.any { newPrefs.contains(it) }) return

            val previousValues = prefs(previousKey).all
            val editor = newPrefs.edit()
            COPIED_KEYS.forEach { key ->
                // Kept as the same type, since the legacy build is saved as a number
                when (val value = previousValues[key]) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                }
            }
            editor.commit()
        }.onFailure { ex ->
            logger.error("Failed to copy analytics identity to new public key: ${ex.message}")
        }
    }

    /**
     * Adds the events from the unfinished batches of [previousKey] and other keys known to be this
     * SDK's to the new key's queue as they are, keeping their messageId, timestamp and userId, then
     * deletes those batches. Includes batches left by an earlier key change that didn't finish. Other
     * keys' batches, like those of Segment's own SDK running in the same app, are left alone. Runs on
     * [dispatcher].
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
                    ?.filter { file -> file.name.endsWith(TMP_SUFFIX) }
                    ?.filter { file ->
                        val batchKey = file.name.removeSuffix(TMP_SUFFIX).substringBeforeLast('-')
                        batchKey != newKey &&
                            (batchKey == previousKey || savedSettingsOwner(batchKey) == SettingsOwner.CUSTOMER_IO)
                    }
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
        private const val PREFS_DIRECTORY = "shared_prefs"
        private const val PREFS_SUFFIX = ".xml"
        private const val INTEGRATIONS = "integrations"
        private const val TMP_SUFFIX = ".tmp"
        private const val BATCH_START = "{\"batch\":["

        private val COPIED_KEYS = listOf(
            Storage.Constants.AnonymousId.rawVal,
            Storage.Constants.UserId.rawVal,
            Storage.Constants.Traits.rawVal,
            Storage.Constants.AppVersion.rawVal,
            Storage.Constants.AppBuild.rawVal,
            Storage.Constants.LegacyAppBuild.rawVal,
            Storage.Constants.DeviceId.rawVal
        )
    }
}
