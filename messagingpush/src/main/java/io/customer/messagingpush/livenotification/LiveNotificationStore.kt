package io.customer.messagingpush.livenotification

import android.content.Context
import androidx.core.content.edit
import java.util.concurrent.TimeUnit

/**
 * Persistent live-notification state (dedicated SharedPreferences file):
 * per-`activity_type` registration signatures, per-`activity_id` last-seen
 * timestamps for the out-of-order guard, per-`activity_id` activity types, and the
 * app-wide opt-in (enabled activity types + branding) that a cold process needs to
 * render at all.
 */
internal class LiveNotificationStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- App-wide config (survives process death) ---

    /**
     * The activity types this app last enabled, or an empty set if it never enabled any.
     *
     * Read in a cold process — one Android started solely to deliver an FCM message, where no app
     * code ran and so `CustomerIO.initialize` never registered the push module. Without this, the
     * module config falls back to its defaults and an empty enabled-types set is ambiguous: it
     * means both "this app never opted in" (ignore the push) and "this app opted in, but this
     * process hasn't loaded that yet" (render the push). Persisting the opt-in separates the two.
     */
    fun enabledActivityTypes(): Set<String> =
        // SharedPreferences owns the instance it returns, so copy instead of retaining it.
        prefs.getStringSet(ENABLED_TYPES_KEY, null)?.toSet().orEmpty()

    /** Replaces (never merges) the persisted opt-in. An empty set removes the entry. */
    fun setEnabledActivityTypes(types: Set<String>) {
        prefs.edit {
            if (types.isEmpty()) {
                remove(ENABLED_TYPES_KEY)
            } else {
                putStringSet(ENABLED_TYPES_KEY, types)
            }
        }
    }

    /** The serialized branding last configured, or null when the app configured none. */
    fun brandingJson(): String? = prefs.getString(BRANDING_KEY, null)

    /** Replaces the persisted branding. Null or blank removes the entry. */
    fun setBrandingJson(json: String?) {
        prefs.edit {
            if (json.isNullOrBlank()) {
                remove(BRANDING_KEY)
            } else {
                putString(BRANDING_KEY, json)
            }
        }
    }

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

        // App-wide entries, deliberately unprefixed. Every sweep in this class
        // ([trimStaleTimestamps], [clearAllActivities], [clearRegistrations]) filters on one of the
        // per-activity prefixes below, so neither TTL reclamation nor logout can clear these — which
        // is intended: an app-wide opt-in is not user-scoped.
        private const val ENABLED_TYPES_KEY = "enabled_types"
        private const val BRANDING_KEY = "branding"

        private const val REG_PREFIX = "reg:"
        private const val TS_PREFIX = "ts:"
        private const val TYPE_PREFIX = "type:"
        private const val END_PREFIX = "end:"

        // Old built-in namespace, replaced by `io.customer.livenotifications.` — used only by migrate().
        private const val LEGACY_ACTIVITY_TYPE_PREFIX = "io.customer.liveactivities."
        private val DEFAULT_TS_TTL_MS = TimeUnit.DAYS.toMillis(7)
    }
}
