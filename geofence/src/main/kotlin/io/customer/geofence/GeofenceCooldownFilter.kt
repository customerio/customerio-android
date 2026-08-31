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
     * Atomically checks the cooldown and records the emit if allowed. Returns true if the
     * caller should proceed to emit, false if the transition is within the cooldown window.
     */
    @Synchronized
    fun tryAcquire(
        userId: String,
        geofenceId: String,
        transition: Event.GeofenceTransition
    ): Boolean {
        val cooldownMs = regionStore.getCachedConfig()?.duplicateEventsExpiry
            ?: GeofenceConstants.DEDUPE_COOLDOWN_MS
        val last = store.getLastEmitTimestamp(userId, geofenceId, transition)
        val now = clock.currentTimeMillis()
        if (last != null && (now - last) < cooldownMs) return false
        store.recordEmit(userId, geofenceId, transition, now)
        // Sweep entries past the max possible cooldown — they can't suppress under any config —
        // to bound the store as fences churn, without the double-fire risk of pruning by cached set.
        store.pruneOlderThan(now - GeofenceConstants.MAX_DUPLICATE_EVENTS_EXPIRY_MS)
        return true
    }

    /**
     * Seconds left on an active cooldown for this key, or null when there is none.
     *
     * Diagnostics only, and kept separate from [tryAcquire] so that method's atomic
     * check-and-record keeps its exact semantics and nobody is tempted to branch on a duration.
     */
    @Synchronized
    fun remainingSeconds(
        userId: String,
        geofenceId: String,
        transition: Event.GeofenceTransition
    ): Double? {
        val last = store.getLastEmitTimestamp(userId, geofenceId, transition) ?: return null
        val cooldownMs = regionStore.getCachedConfig()?.duplicateEventsExpiry
            ?: GeofenceConstants.DEDUPE_COOLDOWN_MS
        val remaining = cooldownMs - (clock.currentTimeMillis() - last)
        return if (remaining > 0) remaining / 1000.0 else null
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
