package io.customer.geofence.replay

import android.location.Location
import io.customer.geofence.polygon.PolygonFixPriority
import io.customer.geofence.polygon.PolygonFreshFixSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The precise-fix request the polygon controller makes mid-callback, driven by the recording.
 *
 * Production's `GmsPolygonFreshFixSource` asks GMS and suspends until a fix arrives or [timeoutMs]
 * elapses. Here the fix arrives as a later `polygon.freshfix.received` stimulus, so [awaitFreshFix]
 * suspends on a deferred the runner completes when it reaches that record. Both the wait and the
 * timeout play out on the virtual clock, so a request the recording never answered
 * (`freshfix.skipped none_arrived`) simply times out to null, exactly as it did in the car — and a
 * request answered by the same cached fix the callback already carried re-enters the controller's
 * duplicate-delivery dedup on the same `elapsedRealtimeNanos`, which is the whole point of feeding
 * it back.
 *
 * Ordered: a drive can have more than one request open, so each `freshfix.received` goes to the
 * oldest waiter, matching the order the SDK asked.
 */
internal class ReplayPolygonFreshFixSource : PolygonFreshFixSource {
    private val waiters = ArrayDeque<CompletableDeferred<Location?>>()

    override suspend fun awaitFreshFix(timeoutMs: Long, priority: PolygonFixPriority): Location? {
        val deferred = CompletableDeferred<Location?>()
        waiters.addLast(deferred)
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            waiters.remove(deferred)
        }
    }

    /** Hands the fix a `polygon.freshfix.received` stimulus carries to the oldest open request. */
    fun deliver(fix: Location) {
        waiters.removeFirstOrNull()?.complete(fix)
    }
}
