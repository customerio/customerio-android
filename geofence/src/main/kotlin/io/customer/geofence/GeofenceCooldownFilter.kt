package io.customer.geofence

import io.customer.geofence.store.GeofenceCooldownStore
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Clock

/**
 * Suppresses duplicate geofence events within the server-configured cooldown window.
 * Windows are user-scoped (matching iOS): after an account switch, the previous
 * user's window never masks the new user's transition on the same fence.
 */
internal class GeofenceCooldownFilter(
    private val store: GeofenceCooldownStore,
    private val regionStore: GeofenceRegionStore,
    private val clock: Clock
) {
    /**
     * Atomically checks the cooldown and records the emit if allowed.
     *
     * `null` when the caller should proceed to emit. Otherwise the seconds still left on the
     * window — a value this check already computes, returned rather than recomputed, so reporting
     * it costs no second read of the store on a background wake.
     */
    @Synchronized
    fun tryAcquire(
        userId: String,
        geofenceId: String,
        transition: Event.GeofenceTransition
    ): Double? {
        val cooldownMs = regionStore.getCachedConfig()?.duplicateEventsExpiry
            ?: GeofenceConstants.DEDUPE_COOLDOWN_MS
        val last = store.getLastEmitTimestamp(userId, geofenceId, transition)
        val now = clock.currentTimeMillis()
        if (last != null) {
            val elapsed = now - last
            if (elapsed < cooldownMs) return (cooldownMs - elapsed) / 1000.0
        }
        store.recordEmit(userId, geofenceId, transition, now)
        // Sweep entries past the max possible cooldown — they can't suppress under any config —
        // to bound the store as fences churn, without the double-fire risk of pruning by cached set.
        store.pruneOlderThan(now - GeofenceConstants.MAX_DUPLICATE_EVENTS_EXPIRY_MS)
        return null
    }

    /**
     * Rolls back a prior [tryAcquire] for this key so a later transition isn't suppressed. Used when
     * the work that followed the acquire couldn't be durably queued, so the crossing can be retried.
     */
    @Synchronized
    fun release(userId: String, geofenceId: String, transition: Event.GeofenceTransition) =
        store.remove(userId, geofenceId, transition)

    @Synchronized
    fun clearAll() = store.clearAll()
}
