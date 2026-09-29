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
     * `null` when the caller should proceed to emit, otherwise the seconds still left on the
     * window. The check already computes that figure, so returning it rather than recomputing
     * keeps reporting a suppression free of a second store read on a background wake.
     *
     * Checks only. [record] is the separate write, so there is nothing to roll back.
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
        // Sweep entries past the max possible cooldown — they can't suppress under any config —
        // to bound the store as fences churn, without the double-fire risk of pruning by cached set.
        store.pruneOlderThan(now - GeofenceConstants.MAX_DUPLICATE_EVENTS_EXPIRY_MS)
    }

    @Synchronized
    fun clearAll() = store.clearAll()
}
