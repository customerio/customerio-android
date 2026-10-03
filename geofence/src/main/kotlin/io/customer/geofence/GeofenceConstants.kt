package io.customer.geofence

internal object GeofenceConstants {
    const val MOVEMENT_TRIGGER_ID = "cio_movement_trigger"

    // Matches the server default and iOS.
    const val FALLBACK_LOCAL_REFRESH_RADIUS_METERS = 1_000f

    // Matches the server default and iOS.
    const val FALLBACK_REMOTE_FETCH_RADIUS_METERS = 5_000f

    // Fallback for `maxBusinessGeofences` when the API config omits it or sends a value outside 0..99.
    const val FALLBACK_MAX_BUSINESS_GEOFENCES = 19

    // Finite so a far-away geofence isn't registered for a local user; local re-rank adds it once
    // the device approaches. The server sends 0 to disable the cap.
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

    // GMS accepts its loitering delay as signed 32-bit milliseconds. This is the largest whole
    // second threshold that can be represented without changing the configured duration.
    const val MAX_DWELL_THRESHOLD_SECONDS = Int.MAX_VALUE / 1_000

    // Fallback for `remoteFetchRefreshExpiry`; movement-trigger EXIT bypasses it so the trigger can
    // re-centre.
    const val STALE_THRESHOLD_MS = 24 * 60 * 60 * 1_000L

    // How old a requested fix may be and still judge containment. `getCurrentLocation` can answer
    // from its cache, so this bounds the cached fix's age, not delivery latency.
    const val MAX_LIVE_FIX_AGE_MS = 5L * 60 * 1_000L

    // How long an outside fix may precede the ENTER it vouches for as an observed crossing. The
    // crossing happened somewhere in that gap, so it bounds how early the real entry could be. An
    // approach session samples every 15 s, so its crossings fit; a passive sample minutes apart
    // does not, and its entry time is honestly unknown to within minutes.
    const val MAX_OUTSIDE_PROOF_AGE_MS = 2L * 60 * 1_000L

    // Clearance, beyond a fix's own accuracy, before it proves the device outside a fence. The
    // same value the polygon evaluator requires of a departure.
    const val OUTSIDE_PROOF_MARGIN_METERS = 20f

    // Fallback for `duplicateEventsExpiry`.
    const val DEDUPE_COOLDOWN_MS = 60 * 60 * 1_000L

    // Backstop on workspace `metadata` so a runaway payload can't bloat a background request; set
    // well above the server, which does the real validation.
    const val MAX_METADATA_COUNT = 100
    const val MAX_METADATA_PAYLOAD_BYTES = 100 * 1024 // 100 KB

    // Attempts before an unreadable queue gives up the wake. Counts WorkManager runs, so network
    // retries share it; fine, since rows stay on disk for a later enqueue or foreground flush.
    const val MAX_WORKER_RUN_ATTEMPTS = 5

    // Google Play services limit on simultaneously registered geofences per app. GMS fails the whole
    // `addGeofences` batch that would exceed it rather than trimming it.
    const val MAX_OS_GEOFENCES = 100

    // One OS slot is reserved for the movement trigger. Server `maxBusinessGeofences` can only
    // lower this.
    const val MAX_OS_BUSINESS_GEOFENCE_SLOTS = MAX_OS_GEOFENCES - 1

    // GMS `setExpirationDuration()` value for "never expires"; the SDK removes geofences itself.
    const val GEOFENCE_EXPIRATION_NEVER = -1L

    // Distinctive so it doesn't collide with the host app's own PendingIntents.
    const val PENDING_INTENT_REQUEST_CODE = 0x4765_6F10
}
