package io.customer.geofence

import android.Manifest
import androidx.annotation.RequiresPermission

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

    /** No `@RequiresPermission`: removal needs none, and broadcast-path cleanups may run without one. */
    suspend fun removeGeofencesByIds(ids: List<String>): Result<Unit>

    /**
     * Separate from [replaceGeofences]: the polygon controller re-sizes the trigger alone, on the
     * broadcast path where the worst case must stay at one GMS call.
     */
    suspend fun replaceMovementTrigger(region: GeofenceRegion): Result<Unit>

    /** No `@RequiresPermission`, as for [removeGeofencesByIds]. */
    suspend fun clearAll(): Result<Unit>
}
