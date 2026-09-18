package io.customer.geofence.polygon

import android.app.PendingIntent
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.tasks.Tasks
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.store.GeofenceRegionStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Exhaustive interleavings of the approach session's lifecycle, checked against invariants rather
 * than expected outputs.
 *
 * Five review rounds on this seam each found the *next* case: a delayed removal listener writing a
 * shared memo back, a cold-process teardown with no local accounting entry, a re-arm that reported
 * an ending the memo never learned about. Every one of them was a path nobody had picked by hand,
 * which is the argument against picking paths by hand. This enumerates every sequence of six
 * operations up to length three, drains whatever is left live, and asserts properties that must
 * hold for all of them.
 *
 * The invariants, for a single monitor instance that armed everything it could remove:
 *
 *  1. **No ending is reported without a count.** An absent `n` means "a session ended and this
 *     process cannot say what it received", which is only true across a process boundary. One
 *     instance that armed its own sessions always has the entry, so absent here is a bug — that is
 *     Bugbot's duplicate, which reported a second ending for a session `start` had already
 *     reported with its count.
 *  2. **No count is reported twice.** Each session is fed a distinct number of samples, so a
 *     repeated count is a session reported twice however it was reached.
 *  3. **Endings never outnumber starts.** `polygon.approach.started` is logged only when a request
 *     actually registers, so more endings than starts means an ending was invented.
 *
 * What this cannot see: anything needing two instances, since the whole point of invariant 1 is
 * that one instance can attribute its own sessions. The cross-process cases are named tests in
 * [PolygonApproachMonitorTest].
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PolygonApproachTeardownInvariantTest : RobolectricTest() {

    private enum class Op {
        /** Re-arm on the live generation, which builds an equal PendingIntent. */
        ARM,

        /** Arm a later generation, which builds a distinct PendingIntent. */
        ARM_NEW_GENERATION,

        /** `stop()` naming nothing, as a teardown from a path that lost its context does. */
        STOP_BARE,

        /** `stop()` naming the live session. */
        STOP_NAMED_CURRENT,

        /** `stop()` naming a session that has already gone, which is how the duplicate appeared. */
        STOP_NAMED_STALE,

        /** Permission revoked mid-session: the request fails and nothing is ever registered. */
        SECURITY_FAILURE
    }

    @Test
    fun everyInterleaving_expectEachSessionReportsOneEndingWithItsOwnCount() {
        val sequences = Op.entries.flatMap { a ->
            Op.entries.flatMap { b -> Op.entries.map { c -> listOf(a, b, c) } }
        }
        val failures = mutableListOf<String>()
        sequences.forEach { sequence ->
            runSequence(sequence)?.let { failures += it }
        }
        // Reported together rather than on the first failure: which sequences fail is the useful
        // output, since one defect usually breaks a family of them.
        check(failures.isEmpty()) {
            "invariant violated by ${failures.size} of ${sequences.size} sequences:\n" +
                failures.take(12).joinToString("\n") { "  $it" }
        }
        check(sequences.size == 216) { "expected every sequence of three, got ${sequences.size}" }
    }

    @Test
    fun theKnownDefectSequences_expectThemCovered() {
        // The two shapes review found, pinned by name so a future reader can see they are in the
        // enumeration above rather than having to trust that they are.
        val rearmThenStaleStop = listOf(Op.ARM, Op.SECURITY_FAILURE, Op.STOP_NAMED_STALE)
        val rearmThenBareStop = listOf(Op.ARM, Op.STOP_BARE, Op.STOP_NAMED_STALE)

        check(runSequence(rearmThenStaleStop) == null) { "regressed: $rearmThenStaleStop" }
        check(runSequence(rearmThenBareStop) == null) { "regressed: $rearmThenBareStop" }
    }

    /** Returns null when the run holds every invariant, or a description of the first violation. */
    private fun runSequence(sequence: List<Op>): String? {
        val client: FusedLocationProviderClient = mockk(relaxed = true)
        val logger: GeofenceLogger = mockk(relaxed = true)
        var generation = 7L
        val store: GeofenceRegionStore = mockk(relaxed = true) {
            every { userStateGeneration() } answers { generation }
        }

        val endings = mutableListOf<Int?>()
        var starts = 0
        every { logger.logPolygonApproachMonitoringStopped(any()) } answers {
            endings += firstArg<Int?>()
        }
        every { logger.logPolygonApproachMonitoringStarted() } answers { starts += 1 }

        var requestFails = false
        every { client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>()) } answers {
            if (requestFails) {
                requestFails = false
                Tasks.forException(SecurityException("permission revoked"))
            } else {
                Tasks.forResult(null)
            }
        }
        every { client.removeLocationUpdates(any<PendingIntent>()) } returns Tasks.forResult(null)

        val monitor = PolygonApproachMonitor(
            context = applicationMock,
            client = client,
            store = store,
            logger = logger,
            backgroundContext = Dispatchers.Unconfined
        )

        var attempts = 0
        var armed = 0
        val deadlines = mutableListOf<Long>()
        fun arm() {
            attempts += 1
            val deadline = SystemClock.elapsedRealtime() + 60_000L + attempts * 1_000L
            val startsBefore = starts
            monitor.start(generation, deadline)
            idle()
            // Only a session that actually registered gets samples and a place in `deadlines`.
            // start() refuses a re-arm while a live session has budget left, and a request that
            // fails never registers; feeding either of those a sample would seed an accounting
            // entry for a session that never existed, which is the harness inventing the defect it
            // is looking for.
            if (starts > startsBefore) {
                armed += 1
                deadlines += deadline
                // A distinct number of samples per session, so a count names which one ended.
                repeat(armed) { monitor.recordSampleDelivered(deadline) }
                idle()
            }
        }

        // Every sequence starts from a live session; the operations are what happens to it.
        arm()
        sequence.forEach { op ->
            when (op) {
                Op.ARM -> arm()
                Op.ARM_NEW_GENERATION -> {
                    generation += 1
                    arm()
                }
                Op.STOP_BARE -> monitor.stop()
                Op.STOP_NAMED_CURRENT -> deadlines.lastOrNull()?.let { monitor.stop(generation, it) }
                Op.STOP_NAMED_STALE -> deadlines.firstOrNull()?.let { monitor.stop(generation, it) }
                Op.SECURITY_FAILURE -> {
                    requestFails = true
                    arm()
                }
            }
            idle()
        }
        // Drain, so a session still live at the end is reported rather than counted as missing.
        monitor.stop()
        idle()

        val name = sequence.joinToString(",") { it.name }
        val counted = endings.filterNotNull()
        return when {
            endings.any { it == null } ->
                "$name: reported an ending with no count, endings=$endings"
            counted.size != counted.distinct().size ->
                "$name: reported the same count twice, endings=$endings"
            endings.size > starts ->
                "$name: ${endings.size} endings against $starts starts, endings=$endings"
            else -> null
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
