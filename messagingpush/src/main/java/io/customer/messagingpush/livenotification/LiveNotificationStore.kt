package io.customer.messagingpush.livenotification

import android.content.Context
import androidx.core.content.edit
import java.util.concurrent.TimeUnit

/**
 * Persistent state for live notifications, backed by a dedicated
 * SharedPreferences file:
 *
 * - **Registration dedup** (per `activity_type`): the last signature
 *   (`token|userId`) registered with the backend, so repeated app launches /
 *   unchanged tokens don't re-POST the registration.
 * - **Out-of-order / dedup guard** (per `activity_id`): the last `timestamp`
 *   seen, so a delayed or duplicate push that is older than one already
 *   rendered is dropped. Unlike iOS (where APNs/ActivityKit order updates), the
 *   Android SDK renders FCM data directly and must guard ordering itself.
 *
 * Timestamp entries are stored with their record time so stale ones (for
 * activities that ended long ago without an explicit `end`) can be trimmed on
 * app launch.
 */
internal class LiveNotificationStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
    // Remembered when an activity is rendered so the host can end it later with
    // just its id: the `end` CDP event needs the `notificationType`, and the SDK
    // already saw it — the host shouldn't have to supply it again.

    /** The activity type last rendered for [activityId], or null if unknown. */
    fun activityType(activityId: String): String? =
        prefs.getString(TYPE_PREFIX + activityId, null)

    fun setActivityType(activityId: String, activityType: String) {
        prefs.edit { putString(TYPE_PREFIX + activityId, activityType) }
    }

    fun clearActivityType(activityId: String) {
        prefs.edit { remove(TYPE_PREFIX + activityId) }
    }

    companion object {
        private const val PREFS_NAME = "io.customer.messagingpush.live_notifications"
        private const val REG_PREFIX = "reg:"
        private const val TS_PREFIX = "ts:"
        private const val TYPE_PREFIX = "type:"
        private val DEFAULT_TS_TTL_MS = TimeUnit.DAYS.toMillis(7)
    }
}
