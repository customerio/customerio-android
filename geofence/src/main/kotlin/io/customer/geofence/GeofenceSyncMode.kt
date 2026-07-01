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
 * How the SDK fetches a user's geofences. Only [FETCH_ALL] ships: the backend returns the full
 * (capped) set and the SDK never sends device location.
 *
 * A location-based "nearby" mode is deliberately absent so there's no code path that transmits
 * device location; re-introducing it needs backend support and a deliberate SDK release (never a
 * runtime/server toggle). Kept as a single-value enum so re-introduction stays a localized change.
 * Prior implementation is in git history.
 */
internal enum class GeofenceSyncMode {
    FETCH_ALL;

    /**
     * FETCH_ALL holds the full set, so movement never triggers a re-fetch. A location-bound mode
     * would re-fetch once the device moved beyond its fetch radius (see class doc).
     */
    fun movementRequiresRemoteFetch(distanceFromAnchor: Float, config: GeofenceConfig): Boolean = false

    companion object {
        val active: GeofenceSyncMode = FETCH_ALL
    }
}
