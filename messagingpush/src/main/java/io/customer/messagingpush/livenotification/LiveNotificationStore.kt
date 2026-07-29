package io.customer.messagingpush.livenotification

import android.content.Context
import androidx.core.content.edit
import java.util.concurrent.TimeUnit

/**
 * Persistent live-notification state (dedicated SharedPreferences file): the
 * app-wide set of enabled activity types, per-`activity_type` registration
 * signatures, per-`activity_id` last-seen timestamps for the out-of-order guard,
 * and per-`activity_id` activity types.
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

    // --- Enabled activity types (app-wide) ---

    /**
     * The activity types the host app last opted into, or an empty set if it never enabled
     * live notifications.
     *
     * Persisted because the opt-in set otherwise lives only in the module config built during
     * SDK initialization. When Android starts a process solely to deliver an FCM push, no app
     * code runs, so nothing rebuilds that config — see [setEnabledActivityTypes].
     */
    fun enabledActivityTypes(): Set<String> =
        prefs.getStringSet(ENABLED_TYPES_KEY, null)
            // getStringSet hands back an instance owned by SharedPreferences that must not be
            // mutated or retained; copy it.
            ?.toSet()
            .orEmpty()

    /**
     * Records the app's opt-in set, replacing any previous value.
     *
     * Replaces rather than merges, and clears the entry when [types] is empty, so an app that
     * stops enabling a type — or the feature entirely — is not still treated as opted in by the
     * next process that starts without running app code.
     */
    fun setEnabledActivityTypes(types: Set<String>) {
        prefs.edit {
            if (types.isEmpty()) remove(ENABLED_TYPES_KEY) else putStringSet(ENABLED_TYPES_KEY, types)
        }
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

    /**
     * Reclaims every per-activity entry (timestamp + activity type + ended marker) whose most
     * recent write is older than [ttlMs]. Intended to run on app launch.
     *
     * All three families are swept, not just `ts:`: a push that arrives without a `timestamp`
     * records an activity type and possibly an ended marker but no timestamp entry, so keying
     * reclamation off `ts:` alone would leak those entries forever and keep inflating
     * [trackedActivityIds]. An id is only dropped once *all* of its present entries have aged
     * out, so a fresh write in one family always keeps the whole id alive.
     */
    fun trimStaleTimestamps(ttlMs: Long = DEFAULT_TS_TTL_MS, now: Long = System.currentTimeMillis()) {
        // activity id -> most recent write time across its entries; null once any entry's write
        // time is unknown but a newer one may still be found (unknown is treated as oldest).
        val lastWriteByActivityId = mutableMapOf<String, Long?>()

        fun record(activityId: String, writtenAt: Long?) {
            if (!lastWriteByActivityId.containsKey(activityId)) {
                lastWriteByActivityId[activityId] = writtenAt
                return
            }
            val known = lastWriteByActivityId[activityId]
            if (known == null || (writtenAt != null && writtenAt > known)) {
                lastWriteByActivityId[activityId] = writtenAt ?: known
            }
        }

        for ((key, value) in prefs.all) {
            val raw = value as? String
            when {
                key.startsWith(TS_PREFIX) ->
                    record(key.removePrefix(TS_PREFIX), raw?.substringAfterLast('|')?.toLongOrNull())
                key.startsWith(TYPE_PREFIX) ->
                    record(key.removePrefix(TYPE_PREFIX), raw?.writeTimeOrNull())
                key.startsWith(END_PREFIX) ->
                    record(key.removePrefix(END_PREFIX), raw?.toLongOrNull())
            }
        }

        val staleActivityIds = lastWriteByActivityId
            .filterValues { lastWrite -> lastWrite == null || now - lastWrite > ttlMs }
            .keys
        if (staleActivityIds.isNotEmpty()) {
            prefs.edit {
                staleActivityIds.forEach {
                    remove(TS_PREFIX + it)
                    remove(TYPE_PREFIX + it)
                    remove(END_PREFIX + it)
                }
            }
        }
    }

    // --- Terminal state (per activity_id) ---

    /**
     * True once [activityId] has reached a terminal state (local end, remote end,
     * or user dismissal). `activity_id`s are unique per activity and `end` is
     * terminal, so any later event for an ended id is stale and must be dropped.
     */
    fun isEnded(activityId: String): Boolean =
        prefs.contains(END_PREFIX + activityId)

    /**
     * Marks [activityId] terminal, returning `true` only if this call set it (i.e.
     * it was not already ended). Callers use the return value to report `end` at
     * most once per id. The marker is never cleared per-id; it is reclaimed by
     * [trimStaleTimestamps] (TTL) and [clearAllActivities] (logout).
     */
    fun markEnded(activityId: String, now: Long = System.currentTimeMillis()): Boolean {
        if (prefs.contains(END_PREFIX + activityId)) return false
        prefs.edit { putString(END_PREFIX + activityId, now.toString()) }
        return true
    }

    // --- Activity type (per activity_id) ---

    /** The activity type last rendered for [activityId], or null if unknown. */
    fun activityType(activityId: String): String? =
        prefs.getString(TYPE_PREFIX + activityId, null)?.let { stored ->
            // Values are stamped `type|writeTimeMillis` so [trimStaleTimestamps] can age them
            // out. Split from the right so a customer-defined type containing '|' round-trips,
            // and fall back to the raw value for entries written before stamping existed.
            if (stored.writeTimeOrNull() != null) stored.substringBeforeLast('|') else stored
        }

    fun setActivityType(activityId: String, activityType: String, now: Long = System.currentTimeMillis()) {
        prefs.edit { putString(TYPE_PREFIX + activityId, "$activityType|$now") }
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

    /** Clears all per-activity state (timestamps + types + ended markers). Used on logout/reset. */
    fun clearAllActivities() {
        prefs.edit {
            prefs.all.keys
                .filter { it.startsWith(TS_PREFIX) || it.startsWith(TYPE_PREFIX) || it.startsWith(END_PREFIX) }
                .forEach { remove(it) }
        }
    }

    /**
     * The trailing `|writeTimeMillis` stamp on a stored value, or null when the value carries
     * no stamp (written by a build predating stamping, so treated as arbitrarily old).
     */
    private fun String.writeTimeOrNull(): Long? =
        if (contains('|')) substringAfterLast('|').toLongOrNull() else null

    companion object {
        private const val PREFS_NAME = "io.customer.messagingpush.live_notifications"

        // Deliberately unprefixed: the reclamation sweeps below key off the `ts:`/`type:`/`end:`
        // prefixes, and this entry is app-wide rather than per-activity, so it must not match.
        private const val ENABLED_TYPES_KEY = "enabled_types"
        private const val REG_PREFIX = "reg:"
        private const val TS_PREFIX = "ts:"
        private const val TYPE_PREFIX = "type:"
        private const val END_PREFIX = "end:"

        // Old built-in namespace, replaced by `io.customer.livenotifications.` — used only by migrate().
        private const val LEGACY_ACTIVITY_TYPE_PREFIX = "io.customer.liveactivities."
        private val DEFAULT_TS_TTL_MS = TimeUnit.DAYS.toMillis(7)
    }
}
