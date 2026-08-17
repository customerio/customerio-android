package io.customer.geofence

/**
 * How polygon geofences are evaluated after the device approaches their enclosing circle.
 */
enum class PolygonTrackingMode {
    /**
     * Uses passive, displacement-gated location batches without starting a foreground service.
     * This is the default and favors Play-policy compatibility and battery life over immediacy.
     */
    RESPONSIVE,

    /**
     * Uses a location foreground service while a polygon is active for lower-latency evaluation.
     *
     * Enable this only when the host app has an independently eligible, user-visible continuous
     * location use case and declares the SDK's polygon location service and required
     * foreground-service permissions in its manifest.
     */
    CONTINUOUS
}
