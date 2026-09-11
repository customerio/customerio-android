package io.customer.geofence

/**
 * What the SDK can ask the OS to monitor. The humble half of geofence registration.
 *
 * The interface exists so a test can substitute the OS without also substituting the SDK. Before
 * it, the only seam was the concrete [GeofenceManager], so a fake had to be a mock of a class that
 * also held the batch-splitting policy, the remove-before-re-add rule and the rollback — meaning a
 * composition that replaced GMS silently replaced those too. The policy stays in [GeofenceManager]
 * behind this interface; a replay double implements the interface and never sees it.
 *
 * Mirrors iOS's `GeofenceRegionMonitoring`, which serves the same purpose against CoreLocation.
 */
internal interface GeofenceRegistrar {
    /**
     * Replaces currently-registered geofences with [regions]; an empty list disables the broadcast
     * receiver. Business IDs in [existingBusinessIds] are left alone — only additions are sent to
     * the OS, because re-upserting a same-ID geofence triggers GMS state reconciliation that can
     * fire spurious EXIT events.
     */
    suspend fun replaceGeofences(
        regions: List<GeofenceRegion>,
        existingBusinessIds: Set<String> = emptySet()
    ): Result<Unit>

    /** Boot-restore entry point; registration is identical to [replaceGeofences]. */
    suspend fun replaceGeofencesForBootRestore(regions: List<GeofenceRegion>): Result<Unit>

    suspend fun removeGeofencesByIds(ids: List<String>): Result<Unit>

    suspend fun clearAll(): Result<Unit>
}
