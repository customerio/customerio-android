package io.customer.geofence

import io.customer.geofence.store.GeofenceCooldownStore
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Clock

/** Suppresses duplicate geofence events within the server-configured cooldown window. */
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
        geofenceId: String,
        transition: Event.GeofenceTransition
    ): Boolean {
        val cooldownMs = regionStore.getCachedConfig()?.duplicateEventsExpiry
            ?: GeofenceConstants.DEDUPE_COOLDOWN_MS
        val last = store.getLastEmitTimestamp(geofenceId, transition)
        val now = clock.currentTimeMillis()
        if (last != null && (now - last) < cooldownMs) return false
        store.recordEmit(geofenceId, transition, now)
        return true
    }

    /**
     * Rolls back a prior [tryAcquire] for this key so a later transition isn't suppressed. Used when
     * the work that followed the acquire couldn't be durably queued, so the crossing can be retried.
     */
    @Synchronized
    fun release(geofenceId: String, transition: Event.GeofenceTransition) =
        store.remove(geofenceId, transition)

    @Synchronized
    fun clearAll() = store.clearAll()
}
