package io.customer.geofence

/**
 * The kind of refresh a signal calls for, decided independently of what triggered it.
 *
 * - [REMOTE] — fetch a fresh set from the API.
 * - [LOCAL]  — re-rank / re-register the cached set on-device, no network.
 * - [SKIP]   — cache is current; do nothing.
 */
internal enum class RefreshAction { REMOTE, LOCAL, SKIP }

/**
 * How the SDK fetches a user's geofences.
 *
 * - [FETCH_ALL] — the backend returns the full (capped) set and the SDK sends no device location.
 * - [NEARBY]    — the SDK sends a coarsened device location and the backend returns the nearby set;
 *   it then re-fetches once the device moves beyond [GeofenceConfig.remoteFetchRefreshTriggerRadius].
 *
 * The active mode is a deliberate SDK-release decision ([active]), never a runtime/server toggle —
 * [NEARBY] is the only mode that transmits device location, so enabling it must be explicit.
 */
internal enum class GeofenceSyncMode {
    FETCH_ALL {
        // FETCH_ALL holds the full set, so movement never triggers a re-fetch — only a local re-rank.
        override fun movementRequiresRemoteFetch(distanceFromAnchor: Float, config: GeofenceConfig): Boolean = false
    },
    NEARBY {
        // The cached set only covers the area around the last fetch; once the device moves past the
        // fetch radius the set is no longer "nearby", so re-fetch from the server.
        override fun movementRequiresRemoteFetch(distanceFromAnchor: Float, config: GeofenceConfig): Boolean =
            distanceFromAnchor >= config.remoteFetchRefreshTriggerRadius
    };

    /** Whether a movement that far from the last-fetch anchor warrants a fresh server fetch. */
    abstract fun movementRequiresRemoteFetch(distanceFromAnchor: Float, config: GeofenceConfig): Boolean

    companion object {
        val active: GeofenceSyncMode = NEARBY
    }
}
