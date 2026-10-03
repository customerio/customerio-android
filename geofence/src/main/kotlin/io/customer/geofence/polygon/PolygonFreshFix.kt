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
 * [BALANCED] suffices for a wake circle hundreds of metres across. [HIGH_ACCURACY] is what a ring
 * needs and costs GPS, so ask for it only once a cheap fix shows it could matter.
 */
internal enum class PolygonFixPriority {
    BALANCED,
    HIGH_ACCURACY
}

/** About accuracy, not age: a callback's fix is already recent but can be too coarse for an edge. */
internal interface PolygonFreshFixSource {
    /**
     * A fix no older than this call, or null within [timeoutMs]. Never throws: missing permission,
     * a disabled provider and a timeout all mean "decide on what you already have".
     */
    suspend fun awaitFreshFix(
        timeoutMs: Long,
        priority: PolygonFixPriority = PolygonFixPriority.HIGH_ACCURACY
    ): Location?
}

internal class GmsPolygonFreshFixSource(
    private val client: FusedLocationProviderClient
) : PolygonFreshFixSource {
    /** Not `@RequiresPermission`: permission can be revoked after any check, so it's handled as null. */
    @SuppressLint("MissingPermission")
    override suspend fun awaitFreshFix(timeoutMs: Long, priority: PolygonFixPriority): Location? {
        if (timeoutMs <= 0L) return null
        val tokenSource = CancellationTokenSource()
        val request = CurrentLocationRequest.Builder()
            .setPriority(
                when (priority) {
                    PolygonFixPriority.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
                    PolygonFixPriority.HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
                }
            )
            // No cached fix: a cached one is the fix we already failed to decide on.
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
            // Also on success: cancelling stops GMS holding the sensor open past the answer.
            tokenSource.cancel()
        }
    }
}
