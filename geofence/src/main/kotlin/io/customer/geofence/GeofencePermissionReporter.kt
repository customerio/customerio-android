package io.customer.geofence

/**
 * Reports the granted location tier, at most once per distinct value per process.
 *
 * Android has no permission observer, so the tier is polled at module init (a background wake may
 * never foreground) and on foreground entry. One shared instance keeps the two from double-reporting.
 */
internal class GeofencePermissionReporter(
    private val permissionChecker: GeofencePermissionChecker,
    private val logger: GeofenceLogger
) {
    private var lastReportedTier: String? = null

    fun reportIfChanged() {
        val tier = when {
            !permissionChecker.hasFineLocationPermission() -> GeofenceLogger.PERMISSION_DENIED
            permissionChecker.isBackgroundDeliveryAvailable() -> GeofenceLogger.PERMISSION_ALWAYS
            else -> GeofenceLogger.PERMISSION_WHEN_IN_USE
        }
        if (tier == lastReportedTier) return
        lastReportedTier = tier
        logger.logPermissionTier(tier)
    }
}
