package io.customer.messagingpush.livenotification

import android.content.Context
import androidx.core.content.edit
import java.util.concurrent.TimeUnit

/**
 * Persistent live-notification state (dedicated SharedPreferences file):
 * per-`activity_type` registration signatures, per-`activity_id` last-seen
 * timestamps for the out-of-order guard, and per-`activity_id` activity types.
 */
internal class LiveNotificationStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * One-time cleanup for the namespace rename: drops registration signatures
     * keyed under the old `io.customer.liveactivities.*` namespace. Idempotent.
     *
     * @return the number of stale registration signatures cleared.
     */
    fun migrate(): Int {
        val stale = prefs.all.keys.filter {
            it.startsWith(REG_PREFIX) && it.contains(LEGACY_ACTIVITY_TYPE_PREFIX)
        }
        if (stale.isNotEmpty()) {
            prefs.edit { stale.forEach { remove(it) } }
        }
        return stale.size
    }

    // --- Registration dedup (per activity_type) ---

    fun registrationSignature(activityType: String): String? =
        prefs.getString(REG_PREFIX + activityType, null)

    fun setRegistrationSignature(activityType: String, signature: String) {
        prefs.edit { putString(REG_PREFIX + activityType, signature) }
    }

    /** Clears all registration signatures, forcing re-registration (e.g. on reset / token deletion). */
    fun clearRegistrations() {
        prefs.edit {
            prefs.all.keys.filter { it.startsWith(REG_PREFIX) }.forEach { remove(it) }
        }
    }

    // --- Out-of-order / dedup guard (per activity_id) ---

    /** The last `timestamp` seen for [activityId], or null if none recorded. */
    fun lastTimestamp(activityId: String): Long? =
        prefs.getString(TS_PREFIX + activityId, null)?.substringBefore('|')?.toLongOrNull()

    fun setLastTimestamp(activityId: String, timestamp: Long, now: Long = System.currentTimeMillis()) {
        prefs.edit { putString(TS_PREFIX + activityId, "$timestamp|$now") }
    }

    fun clearTimestamp(activityId: String) {
        prefs.edit { remove(TS_PREFIX + activityId) }
    }

    /** Removes timestamp entries (and their paired activity types) recorded longer than [ttlMs] ago. Intended to run on app launch. */
    fun trimStaleTimestamps(ttlMs: Long = DEFAULT_TS_TTL_MS, now: Long = System.currentTimeMillis()) {
        val staleActivityIds = prefs.all.entries.filter { (key, value) ->
            key.startsWith(TS_PREFIX) &&
                ((value as? String)?.substringAfter('|', "")?.toLongOrNull()?.let { now - it > ttlMs } ?: true)
        }.map { it.key.removePrefix(TS_PREFIX) }
        if (staleActivityIds.isNotEmpty()) {
            prefs.edit {
                staleActivityIds.forEach {
                    remove(TS_PREFIX + it)
                    remove(TYPE_PREFIX + it)
                }
            }
        }
    }

    // --- Activity type (per activity_id) ---

    /** The activity type last rendered for [activityId], or null if unknown. */
    fun activityType(activityId: String): String? =
        prefs.getString(TYPE_PREFIX + activityId, null)

    fun setActivityType(activityId: String, activityType: String) {
        prefs.edit { putString(TYPE_PREFIX + activityId, activityType) }
    }

    fun clearActivityType(activityId: String) {
        prefs.edit { remove(TYPE_PREFIX + activityId) }
    }

    /** Every activity id the SDK currently tracks (rendered and not yet ended). */
    fun trackedActivityIds(): Set<String> =
        prefs.all.keys
            .filter { it.startsWith(TYPE_PREFIX) }
            .map { it.removePrefix(TYPE_PREFIX) }
            .toSet()

    /** Clears all per-activity state (timestamps + types). Used on logout/reset. */
    fun clearAllActivities() {
        prefs.edit {
            prefs.all.keys
                .filter { it.startsWith(TS_PREFIX) || it.startsWith(TYPE_PREFIX) }
                .forEach { remove(it) }
        }
    }

    companion object {
        private const val PREFS_NAME = "io.customer.messagingpush.live_notifications"
        private const val REG_PREFIX = "reg:"
        private const val TS_PREFIX = "ts:"
        private const val TYPE_PREFIX = "type:"

        // Old built-in namespace, replaced by `io.customer.livenotifications.` — used only by migrate().
        private const val LEGACY_ACTIVITY_TYPE_PREFIX = "io.customer.liveactivities."
        private val DEFAULT_TS_TTL_MS = TimeUnit.DAYS.toMillis(7)
    }
}
