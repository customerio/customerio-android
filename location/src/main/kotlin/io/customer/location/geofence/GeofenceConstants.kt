package io.customer.location.geofence

/** Configurable thresholds and identifiers for geofence monitoring. */
internal object GeofenceConstants {
    // Sentinel ID for the movement-trigger geofence so we can distinguish it from
    // business geofences when applying separate INITIAL_TRIGGER strategies.
    const val MOVEMENT_TRIGGER_ID = "cio_movement_trigger"

    // Fallback for the movement trigger's OS geofence radius (meters). API field:
    // `local_refresh_trigger_radius`.
    const val FALLBACK_LOCAL_REFRESH_RADIUS_METERS = 1_000f

    // Fallback for the distance from the last API anchor at which an EXIT escalates
    // to a fresh API fetch (meters). API field: `remote_fetch_refresh_trigger_radius`.
    const val FALLBACK_REMOTE_FETCH_RADIUS_METERS = 5_000f

    // Fallback for `maxBusinessGeofences` from the API config. Matches the
    // historical cap so customers aren't punished by a misconfigured backend.
    const val FALLBACK_MAX_BUSINESS_GEOFENCES = 19

    // Minimum interval between server fetches. Non-forced refresh calls (identify,
    // app launch) skip the API call when a successful sync happened within this
    // window. Movement-trigger EXIT bypasses this so the loop can update the
    // trigger's center. Doubles as the fallback for `remoteFetchRefreshExpiryMs`
    // when the API config field is missing or non-positive.
    const val STALE_THRESHOLD_MS = 24 * 60 * 60 * 1_000L

    // Duplicate-transition suppression window used by GeofenceCooldownFilter.
    // Doubles as the fallback for `duplicateEventsExpiryMs` from the API config
    // when the field is missing or non-positive.
    const val DEDUPE_COOLDOWN_MS = 60 * 60 * 1_000L

    // GMS `GeofencingRequest.Builder().setInitialTrigger()` flag for "no initial
    // events on registration". Used for the movement trigger so re-registration
    // doesn't spuriously fire EXIT.
    const val NO_INITIAL_TRIGGER = 0

    // GMS `Geofence.Builder().setExpirationDuration()` flag for "never expires".
    // Our geofences are managed at the application level (we remove explicitly)
    // so OS-side expiration is disabled.
    const val GEOFENCE_EXPIRATION_NEVER = -1L

    // Unique request code for the `PendingIntent` we hand to GMS so it doesn't
    // collide with other PendingIntents in the host app's process.
    const val PENDING_INTENT_REQUEST_CODE = 0x4765_6F10
}
