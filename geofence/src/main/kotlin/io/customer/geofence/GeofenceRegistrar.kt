package io.customer.geofence

import android.Manifest
import androidx.annotation.RequiresPermission

/** The OS side of registration ([GeofenceManager]), so callers can be tested without GMS. */
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
     * No `@RequiresPermission`: `GeofencingClient.removeGeofences` needs no location permission, and
     * the broadcast-path cleanups that call this may run without one.
     */
    suspend fun removeGeofencesByIds(ids: List<String>): Result<Unit>

    /**
     * Replaces just the movement trigger, leaving business registrations untouched. Separate from
     * [replaceGeofences] because the polygon controller re-sizes the trigger on its own, on the
     * broadcast path where the worst case must stay at one GMS call.
     */
    suspend fun replaceMovementTrigger(region: GeofenceRegion): Result<Unit>

    /** Needs no location permission, for the same reason as [removeGeofencesByIds]. */
    suspend fun clearAll(): Result<Unit>
}
