package io.customer.location.geofence

import io.customer.location.geofence.store.GeofenceCooldownStore
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Clock

/** Suppresses duplicate geofence events within a configurable cooldown window. */
internal class GeofenceCooldownFilter(
    private val store: GeofenceCooldownStore,
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
        val last = store.getLastEmitTimestamp(geofenceId, transition)
        val now = clock.currentTimeMillis()
        if (last != null && (now - last) < GeofenceConstants.DEDUPE_COOLDOWN_MS) return false
        store.recordEmit(geofenceId, transition, now)
        return true
    }

    @Synchronized
    fun clearAll() = store.clearAll()
}
