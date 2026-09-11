package io.customer.geofence

import android.Manifest
import androidx.annotation.RequiresPermission

/**
 * What the SDK can ask the OS to monitor: the humble half of registration, so a test can
 * substitute GMS without also substituting the batch policy in [GeofenceManager].
 */
internal interface GeofenceRegistrar {
    /**
     * Ids in [existingBusinessIds] are not re-sent: re-upserting a same-id geofence makes GMS
     * reconcile state and fire spurious EXITs.
     */
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    suspend fun replaceGeofences(
        regions: List<GeofenceRegion>,
        existingBusinessIds: Set<String> = emptySet()
    ): Result<Unit>

    /** Boot-restore entry point; registration is identical to [replaceGeofences]. */
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    suspend fun replaceGeofencesForBootRestore(regions: List<GeofenceRegion>): Result<Unit>

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    suspend fun removeGeofencesByIds(ids: List<String>): Result<Unit>

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    suspend fun clearAll(): Result<Unit>
}
