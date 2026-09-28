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
     * `null` when the caller should emit, otherwise the seconds left on the window (returned so
     * logging a suppression needs no second store read). Read-only; [record] is the write.
     */
    @Synchronized
    fun suppressedForSeconds(
        userId: String,
        geofenceId: String,
        transition: Event.GeofenceTransition
    ): Double? {
        val cooldownMs = regionStore.getCachedConfig()?.duplicateEventsExpiry
            ?: GeofenceConstants.DEDUPE_COOLDOWN_MS
        val last = store.getLastEmitTimestamp(userId, geofenceId, transition) ?: return null
        val elapsed = clock.currentTimeMillis() - last
        return if (elapsed < cooldownMs) (cooldownMs - elapsed) / 1000.0 else null
    }

    @Synchronized
    fun record(
        userId: String,
        geofenceId: String,
        transition: Event.GeofenceTransition
    ) {
        val now = clock.currentTimeMillis()
        store.recordEmit(userId, geofenceId, transition, now)
        // Prune by age past the max possible cooldown, not by cached fence set: an aged entry can't
        // suppress under any config, while pruning a live window would let a repeat double-fire.
        store.pruneOlderThan(now - GeofenceConstants.MAX_DUPLICATE_EVENTS_EXPIRY_MS)
    }

    @Synchronized
    fun clearAll() = store.clearAll()
}
