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

    /** Removes timestamp entries recorded longer than [ttlMs] ago. Intended to run on app launch. */
    fun trimStaleTimestamps(ttlMs: Long = DEFAULT_TS_TTL_MS, now: Long = System.currentTimeMillis()) {
        val staleKeys = prefs.all.entries.filter { (key, value) ->
            key.startsWith(TS_PREFIX) &&
                ((value as? String)?.substringAfter('|', "")?.toLongOrNull()?.let { now - it > ttlMs } ?: true)
        }.map { it.key }
        if (staleKeys.isNotEmpty()) {
            prefs.edit { staleKeys.forEach { remove(it) } }
        }
    }

    companion object {
        private const val PREFS_NAME = "io.customer.messagingpush.live_notifications"
        private const val REG_PREFIX = "reg:"
        private const val TS_PREFIX = "ts:"
        private val DEFAULT_TS_TTL_MS = TimeUnit.DAYS.toMillis(7)
    }
}
