package io.customer.geofence

/**
 * The kind of refresh a signal calls for, decided independently of what triggered it.
 *
 * - [REMOTE] — fetch a fresh set from the API.
 * - [LOCAL]  — re-rank / re-register the cached set on-device, no network.
 * - [SKIP]   — cache is current; do nothing.
 */
internal enum class RefreshAction { REMOTE, LOCAL, SKIP }
