package io.customer.geofence.polygon

import android.annotation.SuppressLint
import android.location.Location
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One precise fix, asked for on a geofence callback whose own fix decided nothing.
 *
 * Not a freshness mechanism, and the distinction is load-bearing. A GMS geofence callback arrives
 * with its triggering fix attached, measured across 54 callbacks at median age 0.6 s and max 9.7 s,
 * so the delivered fix is never old. What it can be is too coarse to place the device on either
 * side of an edge, and that is the only reason this exists. iOS gates its equivalent on a 30 s age
 * because their region callbacks carry no location at all and they read a cache that can be hours
 * stale; copying that gate here would be dead code.
 *
 * An interface rather than the GMS client directly: this is awaited on a broadcast's budget, so a
 * test has to be able to make it return late, return nothing, or throw, and none of those are
 * reachable through the real client.
 */
/**
 * What the caller is willing to spend on a fix.
 *
 * [BALANCED] answers "roughly where is this device", which is all that is needed to tell whether it
 * is inside a wake circle hundreds of metres across. [HIGH_ACCURACY] is what a ring needs and what
 * costs GPS, so it is asked for only once a cheap fix has shown it could matter.
 */
internal enum class PolygonFixPriority {
    BALANCED,
    HIGH_ACCURACY
}

internal interface PolygonFreshFixSource {
    /**
     * A fix no older than this call, or null if none arrived within [timeoutMs].
     *
     * Never throws: a missing permission, a disabled provider and a timeout are all the same
     * outcome to the caller, which is "decide on what you already have".
     */
    suspend fun awaitFreshFix(
        timeoutMs: Long,
        priority: PolygonFixPriority = PolygonFixPriority.HIGH_ACCURACY
    ): Location?
}

internal class GmsPolygonFreshFixSource(
    private val client: FusedLocationProviderClient
) : PolygonFreshFixSource {
    /**
     * Deliberately not `@RequiresPermission`, for the reason [PolygonApproachMonitor.start] gives:
     * permission can be revoked between any check and this call, and the callers are broadcasts in
     * a possibly cold process that cannot hold one either way. The missing-permission case is a
     * handled outcome, not a precondition.
     */
    @SuppressLint("MissingPermission")
    override suspend fun awaitFreshFix(timeoutMs: Long, priority: PolygonFixPriority): Location? {
        if (timeoutMs <= 0L) return null
        val tokenSource = CancellationTokenSource()
        val request = CurrentLocationRequest.Builder()
            // The point of asking. Every fix we evaluate today arrives on a GMS geofence
            // callback, whose accuracy is GMS's choice and not ours: measured across four field
            // captures it degrades to 100-1000 m when the device is slow or parked, which reads as
            // network positioning rather than GNSS. This request is the only fix in the pipeline
            // whose accuracy class we pick.
            .setPriority(
                when (priority) {
                    PolygonFixPriority.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
                    PolygonFixPriority.HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
                }
            )
            // No cached fix, because a cached one is the fix we already failed to decide on. The
            // caller is asking for an accuracy class, not for a younger timestamp.
            .setMaxUpdateAgeMillis(0L)
            .setDurationMillis(timeoutMs)
            .build()
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { tokenSource.cancel() }
                    client.getCurrentLocation(request, tokenSource.token)
                        .addOnSuccessListener { location ->
                            if (continuation.isActive) continuation.resumeWith(Result.success(location))
                        }
                        .addOnFailureListener {
                            if (continuation.isActive) continuation.resumeWith(Result.success(null))
                        }
                }
            }
        } catch (e: SecurityException) {
            null
        } finally {
            // Also on the success path: the token is what stops GMS holding the sensor open past
            // the answer we already have.
            tokenSource.cancel()
        }
    }
}
