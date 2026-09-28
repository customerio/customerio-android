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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Every sequence of three approach-session lifecycle operations, drained and then checked against
 * invariants rather than expected outputs. For one monitor instance that armed everything it can
 * remove:
 *
 *  1. **No ending is reported without a count.** An absent `n` is only true across a process
 *     boundary; one instance always has the entry for a session it armed.
 *  2. **No count is reported twice.** Each session is fed a distinct number of samples, so a
 *     repeated count is a session reported twice.
 *  3. **Endings never outnumber starts.** `polygon.approach.started` is logged only when a request
 *     actually registers.
 *
 * Cross-process cases need two instances and are named tests in [PolygonApproachMonitorTest].
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

        /** `stop()` naming the first session, which may already have ended. */
        STOP_NAMED_STALE,

        /** Permission revoked mid-session: the request fails and nothing is ever registered. */
        SECURITY_FAILURE,

        /** A batch for the first session, which may by now have ended. */
        LATE_SAMPLE,

        /** The cold-process order: a batch is recorded, then the session it belongs to is adopted. */
        ADOPT_AFTER_SAMPLE,

        /** A stale batch for an earlier generation, another route to a repeat removal. */
        REMOVE_STALE_GENERATION,

        /**
         * A removal GMS rejects once and accepts on the retry. The retry must carry both session
         * keys, or its success looks like a first teardown.
         */
        REMOVAL_FAILS_ONCE
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
        val ops = Op.entries.size
        check(sequences.size == ops * ops * ops) {
            "expected every sequence of three, got ${sequences.size}"
        }
    }

    @Test
    fun theKnownDefectSequences_expectThemCovered() {
        // Named so these sequences are visibly part of the enumeration above.
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

        // Scheduler-backed rather than unconfined: every job the monitor launches opens with a
        // delay, and only REMOVAL_FAILS_ONCE advances time. The session timeout, a minute out,
        // stays unreached.
        val scheduler = TestCoroutineScheduler()
        var requestFails = false
        var removalFailsOnce = false
        every { client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>()) } answers {
            if (requestFails) {
                requestFails = false
                Tasks.forException(SecurityException("permission revoked"))
            } else {
                Tasks.forResult(null)
            }
        }
        every { client.removeLocationUpdates(any<PendingIntent>()) } answers {
            if (removalFailsOnce) {
                removalFailsOnce = false
                Tasks.forException(IllegalStateException("remove rejected"))
            } else {
                Tasks.forResult(null)
            }
        }

        val monitor = PolygonApproachMonitor(
            context = applicationMock,
            client = client,
            store = store,
            logger = logger,
            backgroundContext = StandardTestDispatcher(scheduler)
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
            // Only a session that actually registered gets samples and a place in `deadlines`:
            // start() refuses a re-arm while a live session has budget left, and a failed request
            // never registers. Sampling either would seed an entry for a nonexistent session.
            if (starts > startsBefore) {
                armed += 1
                deadlines += deadline
                // A distinct number of samples per session, so a count names which one ended.
                repeat(armed * SAMPLES_PER_SESSION) { monitor.recordSampleDelivered(deadline) }
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
                Op.LATE_SAMPLE -> deadlines.firstOrNull()?.let { monitor.recordSampleDelivered(it) }
                Op.REMOVE_STALE_GENERATION -> monitor.removeStaleGeneration(7L)
                Op.REMOVAL_FAILS_ONCE -> {
                    removalFailsOnce = true
                    deadlines.lastOrNull()?.let { monitor.stop(generation, it) } ?: monitor.stop()
                    idle()
                    scheduler.advanceTimeBy(FIRST_RETRY_BACKOFF_MS + 1L)
                    scheduler.runCurrent()
                    idle()
                    removalFailsOnce = false
                }
                Op.ADOPT_AFTER_SAMPLE -> {
                    attempts += 1
                    val deadline = SystemClock.elapsedRealtime() + 60_000L + attempts * 1_000L
                    monitor.recordSampleDelivered(deadline)
                    val startsBefore = starts
                    monitor.start(generation, deadline)
                    idle()
                    if (starts > startsBefore) {
                        armed += 1
                        deadlines += deadline
                        repeat(armed * SAMPLES_PER_SESSION - 1) { monitor.recordSampleDelivered(deadline) }
                        idle()
                    }
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

    private companion object {
        /**
         * Samples per session, in tens, so a late batch landing in one session's count cannot make
         * it collide with another session's and read as a duplicate report.
         */
        const val SAMPLES_PER_SESSION = 10

        /** The monitor's first removal-retry delay, which the retry op has to step over. */
        const val FIRST_RETRY_BACKOFF_MS = 5_000L
    }
}
