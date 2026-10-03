package io.customer.geofence.replay

import android.location.Location
import io.customer.geofence.polygon.PolygonFixPriority
import io.customer.geofence.polygon.PolygonFreshFixSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Answers the polygon controller's fresh-fix request from a later `polygon.freshfix.received`
 * stimulus. The timeout runs on the replay's clock, so a request the recording never answered times
 * out to null as it did on the device.
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
     * Answers the oldest open request; false when none is waiting, which the caller reports as a
     * divergence.
     */
    fun deliver(fix: Location): Boolean =
        waiters.removeFirstOrNull()?.complete(fix) ?: false
}
