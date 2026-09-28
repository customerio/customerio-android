package io.customer.geofence

/**
 * [REMOTE] fetches from the API, [LOCAL] re-ranks and re-registers the cached set with no network,
 * [SKIP] does nothing because the cache is current.
 */
internal enum class RefreshAction { REMOTE, LOCAL, SKIP }
