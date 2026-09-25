package io.customer.geofence

/**
 * Reports the granted location tier, at most once per distinct value per process.
 *
 * Android has no permission observer, so the tier has to be polled. It used to be polled only from
 * [GeofenceLifecycleObserver.onStart] — foreground entry — which meant a cold background wake
 * reported nothing at all. That is the session a drive actually records: the process is woken by a
 * geofence broadcast, does its work and dies without ever foregrounding, so every capture of the
 * case that matters most was silent about the permission it was operating under.
 *
 * Shared between module init and foreground entry so the two do not double-report the same tier.
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
