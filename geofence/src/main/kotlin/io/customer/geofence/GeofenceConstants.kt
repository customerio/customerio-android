package io.customer.geofence

/** Configurable thresholds and identifiers for geofence monitoring. */
internal object GeofenceConstants {
    // Sentinel ID for the movement-trigger geofence so we can distinguish it from
    // business geofences when applying separate INITIAL_TRIGGER strategies.
    const val MOVEMENT_TRIGGER_ID = "cio_movement_trigger"

    // Fallback for `localRefreshTriggerRadius` when the API config omits it.
    const val FALLBACK_LOCAL_REFRESH_RADIUS_METERS = 3_000f

    // Fallback for `remoteFetchRefreshTriggerRadius` when the API config omits it.
    const val FALLBACK_REMOTE_FETCH_RADIUS_METERS = 20_000f

    // Fallback for `maxBusinessGeofences`. Matches the historical cap so customers
    // aren't punished by a misconfigured backend.
    const val FALLBACK_MAX_BUSINESS_GEOFENCES = 19

    // Fallback for `maxMonitoringDistance`. Finite so a far-away geofence (e.g. another
    // continent) isn't registered for a local user; local re-rank adds it once the device
    // approaches. The server sends 0 to disable the cap.
    const val FALLBACK_MAX_MONITORING_DISTANCE_METERS = 1_000_000f // 1000 km
    const val NO_MONITORING_DISTANCE_CAP_METERS = Float.MAX_VALUE

    // Sane bounds the SDK clamps server config into: a positive out-of-range value clamps to the
    // bound; a non-positive value falls back.
    const val MIN_LOCAL_REFRESH_RADIUS_METERS = 100f
    const val MAX_LOCAL_REFRESH_RADIUS_METERS = 5_000f
    const val MIN_REMOTE_FETCH_REFRESH_EXPIRY_MS = 60_000L // 1 minute
    const val MAX_REMOTE_FETCH_REFRESH_EXPIRY_MS = 7L * 24 * 60 * 60 * 1_000L // 7 days
    const val MIN_DUPLICATE_EVENTS_EXPIRY_MS = 60_000L // 1 minute
    const val MAX_DUPLICATE_EVENTS_EXPIRY_MS = 24L * 60 * 60 * 1_000L // 24 hours

    // Minimum interval between server fetches. Non-forced refresh calls (identify,
    // app launch) skip the API call when a successful sync happened within this
    // window. Movement-trigger EXIT bypasses this so the loop can update the
    // trigger's center. Doubles as the fallback for `remoteFetchRefreshExpiry`
    // when the API config field is missing or non-positive.
    const val STALE_THRESHOLD_MS = 24 * 60 * 60 * 1_000L

    // Duplicate-transition suppression window used by GeofenceCooldownFilter.
    // Doubles as the fallback for `duplicateEventsExpiry` from the API config
    // when the field is missing or non-positive.
    const val DEDUPE_COOLDOWN_MS = 60 * 60 * 1_000L

    // GMS `Geofence.Builder().setExpirationDuration()` flag for "never expires".
    // Our geofences are managed at the application level (we remove explicitly)
    // so OS-side expiration is disabled.
    const val GEOFENCE_EXPIRATION_NEVER = -1L

    // Unique request code for the `PendingIntent` we hand to GMS so it doesn't
    // collide with other PendingIntents in the host app's process.
    const val PENDING_INTENT_REQUEST_CODE = 0x4765_6F10
}
