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

    /**
     * No `@RequiresPermission`: `GeofencingClient.removeGeofences` needs no location permission,
     * and declaring one made lint refuse the two callers that legitimately have none — an orphan
     * cleanup on a broadcast and the sign-out clear.
     */
    suspend fun removeGeofencesByIds(ids: List<String>): Result<Unit>

    /** Needs no location permission, for the same reason as [removeGeofencesByIds]. */
    /**
     * Replaces just the movement trigger, leaving business registrations untouched. Separate from
     * [replaceGeofences] because the polygon controller re-sizes the trigger on its own, on the
     * broadcast path where the worst case must stay at one GMS call.
     */
    suspend fun replaceMovementTrigger(region: GeofenceRegion): Result<Unit>

    suspend fun clearAll(): Result<Unit>
}
