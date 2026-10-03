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
 * Runs every three-op approach-session sequence on one monitor and checks invariants: no ending
 * without a count, no count reported twice, no more endings than starts. Cross-process cases are
 * in [PolygonApproachMonitorTest].
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PolygonApproachTeardownInvariantTest : RobolectricTest() {

    private enum class Op {
        /** Re-arm on the live generation, which builds an equal PendingIntent. */
        ARM,

        /** Arm a later generation, which builds a distinct PendingIntent. */
        ARM_NEW_GENERATION,

        STOP_BARE,

        STOP_NAMED_CURRENT,

        STOP_NAMED_STALE,

        SECURITY_FAILURE,

        LATE_SAMPLE,

        /** The cold-process order: a batch is recorded, then its session is adopted. */
        ADOPT_AFTER_SAMPLE,

        REMOVE_STALE_GENERATION,

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
        // Collected, not failed fast: one defect usually breaks a family of sequences.
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

        // Scheduler-backed, not unconfined: monitor jobs open with a delay and only
        // REMOVAL_FAILS_ONCE advances time, so the one-minute session timeout never fires.
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
            // start() refuses a re-arm with budget left and a failed request never registers;
            // sampling either would seed an entry for a nonexistent session.
            if (starts > startsBefore) {
                armed += 1
                deadlines += deadline
                // A distinct number of samples per session, so a count names which one ended.
                repeat(armed * SAMPLES_PER_SESSION) { monitor.recordSampleDelivered(deadline) }
                idle()
            }
        }

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
        /** Tens apart, so a late batch in one session's count can't collide with another's. */
        const val SAMPLES_PER_SESSION = 10

        /** The monitor's first removal-retry delay, which the retry op has to step over. */
        const val FIRST_RETRY_BACKOFF_MS = 5_000L
    }
}
