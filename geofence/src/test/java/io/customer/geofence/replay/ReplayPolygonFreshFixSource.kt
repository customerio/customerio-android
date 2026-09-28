package io.customer.geofence.replay

import android.location.Location
import io.customer.geofence.polygon.PolygonFixPriority
import io.customer.geofence.polygon.PolygonFreshFixSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The precise-fix request the polygon controller makes mid-callback, answered from the recording.
 *
 * The fix arrives as a later `polygon.freshfix.received` stimulus, which the runner passes to
 * [deliver]. The wait and the timeout both run on the replay's clock, so a request the recording
 * never answered times out to null as it did on the device. Requests are answered oldest first,
 * the order the SDK asked.
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

    /**
     * Hands [fix] to the oldest open request. False when none is waiting (the replay stopped asking
     * earlier than the device did), which the caller reports as a divergence.
     */
    fun deliver(fix: Location): Boolean =
        waiters.removeFirstOrNull()?.complete(fix) ?: false
}
