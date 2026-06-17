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
 * [FETCH_ALL] is the active default: the backend returns the full (capped) set and the SDK never
 * sends device location to fetch.
 *
 * [NEARBY] sends coarse location and lets the backend return only nearby geofences. It is retained
 * and tested so location-based fetch can be re-enabled in a later phase by changing [active] — that
 * also needs backend support and is a deliberate SDK release, never a runtime or server-pushed
 * toggle (which would re-introduce the ability to silently start sending location).
 */
internal enum class GeofenceSyncMode {
    FETCH_ALL,
    NEARBY;

    /**
     * NEARBY's set is location-bound, so it re-fetches once the device outruns it; FETCH_ALL holds
     * the full set, so movement never re-fetches.
     */
    fun movementRequiresRemoteFetch(distanceFromAnchor: Float, config: GeofenceConfig): Boolean = when (this) {
        NEARBY -> distanceFromAnchor >= config.remoteFetchRefreshTriggerRadius
        FETCH_ALL -> false
    }

    companion object {
        val active: GeofenceSyncMode = FETCH_ALL
    }
}
