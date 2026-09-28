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

/**
 * One fresh fix, requested when a geofence callback's own fix decided nothing, and by the periodic
 * re-check.
 *
 * About accuracy, not age: a GMS geofence callback already carries a recent fix, but it can be too
 * coarse to place the device on either side of an edge, so no age gate is needed.
 */
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
     * Not `@RequiresPermission`: permission can be revoked between any check and this call, so a
     * missing permission is a handled outcome (null), not a precondition.
     */
    @SuppressLint("MissingPermission")
    override suspend fun awaitFreshFix(timeoutMs: Long, priority: PolygonFixPriority): Location? {
        if (timeoutMs <= 0L) return null
        val tokenSource = CancellationTokenSource()
        val request = CurrentLocationRequest.Builder()
            // The only fix in the pipeline whose accuracy class we pick; a geofence callback's fix
            // arrives at whatever accuracy GMS chose.
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
