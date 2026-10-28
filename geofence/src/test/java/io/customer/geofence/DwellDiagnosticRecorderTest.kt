package io.customer.geofence

import android.content.Context
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.DwellDiagnosticEvent.DEADLINE_SCHEDULED
import io.customer.geofence.DwellDiagnosticEvent.DWELL_EMITTED
import io.customer.geofence.DwellDiagnosticEvent.FIX_RECEIVED
import io.customer.geofence.DwellDiagnosticEvent.FIX_REQUESTED
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Real journal and preferences: the contract is what reaches storage, and in what order. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DwellDiagnosticRecorderTest : RobolectricTest() {
    @Test
    fun invalidateAndClear_givenLateRecordWithRetiredToken_expectOnlyRecordsMadeAfterPersist() = runTest {
        val env = Env(testScheduler)
        try {
            env.recorder.record(record(DEADLINE_SCHEDULED, 1))
            advanceUntilIdle()
            env.journal.snapshot()?.map { it.event } shouldBeEqualTo listOf(DEADLINE_SCHEDULED)

            // Captured before a provider await, as GmsPolygonFreshFixSource does.
            val oldToken = env.recorder.currentToken()
            env.recorder.record(record(FIX_REQUESTED, 2), oldToken)
            env.recorder.invalidateAndClear()
            // The old request's answer lands after the reset, then the next session records.
            env.recorder.record(record(FIX_RECEIVED, 3), oldToken)
            env.recorder.record(record(DWELL_EMITTED, 4))
            advanceUntilIdle()

            env.journal.snapshot()?.map { it.event } shouldBeEqualTo listOf(DWELL_EMITTED)
        } finally {
            env.scopeJob.cancel()
        }
    }

    @Test
    fun invalidateAndClear_givenScopeCancelledBeforeClearRuns_expectJournalStillErased() = runTest {
        val env = Env(testScheduler)
        env.recorder.record(record(DEADLINE_SCHEDULED, 1))
        advanceUntilIdle()
        env.journal.snapshot()?.map { it.event } shouldBeEqualTo listOf(DEADLINE_SCHEDULED)

        env.recorder.invalidateAndClear()
        // An SDK reset cancels the scope before the queued clear runs.
        env.scopeJob.cancel()
        advanceUntilIdle()

        env.journal.snapshot() shouldBeEqualTo emptyList()
    }

    @Test
    fun record_givenQueueDrained_expectWriterEndsAndNextRecordStillLands() = runTest {
        val env = Env(testScheduler)
        try {
            env.recorder.record(record(DEADLINE_SCHEDULED, 1))
            advanceUntilIdle()
            // Production's geofence scope is never cancelled, so a writer left waiting would live forever.
            env.scopeJob.children.toList() shouldBeEqualTo emptyList()

            env.recorder.record(record(DWELL_EMITTED, 2))
            advanceUntilIdle()

            env.journal.snapshot()?.map { it.event } shouldBeEqualTo listOf(DEADLINE_SCHEDULED, DWELL_EMITTED)
            env.scopeJob.children.toList() shouldBeEqualTo emptyList()
        } finally {
            env.scopeJob.cancel()
        }
    }

    @Test
    fun prepareOwner_givenProcessDiedBeforeIdentityClear_expectOldHistoryRemovedAndSameOwnerRelaunchRetained() = runTest {
        var owner: Long? = 1L
        val old = Env(testScheduler, ownerProvider = { owner })
        old.recorder.record(DwellDiagnosticRecord(DEADLINE_SCHEDULED, 1, fenceId = "a"))
        advanceUntilIdle()
        old.journal.snapshot()?.map { it.fenceId } shouldBeEqualTo listOf("a")
        old.scopeJob.cancel() // The process ends before it can queue or run an identity clear.

        owner = 2L
        val next = Env(testScheduler, ownerProvider = { owner })
        try {
            next.recorder.prepareOwner()
            advanceUntilIdle()
            // Inspect persisted bytes without an export-time owner repair: startup itself must erase them.
            next.journal.snapshot() shouldBeEqualTo emptyList()
            next.recorder.record(DwellDiagnosticRecord(DEADLINE_SCHEDULED, 2, fenceId = "b"))
            advanceUntilIdle()
            val reopened = Env(testScheduler, ownerProvider = { owner })
            try {
                reopened.recorder.prepareOwner()
                advanceUntilIdle()
                reopened.recorder.snapshot()?.map { it.fenceId } shouldBeEqualTo listOf("b")
            } finally {
                reopened.scopeJob.cancel()
            }
        } finally {
            next.scopeJob.cancel()
        }
    }

    @Test
    fun record_givenOwnerChangesDuringAwaitOrBecomesUnknown_expectRetiredAnswersDroppedAndHistoryErased() = runTest {
        var owner: Long? = 1L
        val env = Env(testScheduler, ownerProvider = { owner })
        try {
            env.recorder.record(DwellDiagnosticRecord(FIX_REQUESTED, 1, fenceId = "a"))
            advanceUntilIdle()
            val token = env.recorder.currentToken()
            owner = 2L
            env.recorder.record(DwellDiagnosticRecord(FIX_RECEIVED, 2, fenceId = "late-a"), token)
            env.recorder.record(DwellDiagnosticRecord(DWELL_EMITTED, 3, fenceId = "b"))
            advanceUntilIdle()
            env.recorder.snapshot()?.map { it.fenceId } shouldBeEqualTo listOf("b")

            owner = null
            env.recorder.prepareOwner()
            env.recorder.record(DwellDiagnosticRecord(FIX_RECEIVED, 4, fenceId = "unowned"))
            advanceUntilIdle()
            env.recorder.snapshot() shouldBeEqualTo emptyList()
        } finally {
            env.scopeJob.cancel()
        }
    }

    @Test
    fun append_givenNewerPersistedOwner_expectOldAppendRefusedAndLegacyOrMalformedMarkerReplaced() = runTest {
        val env = Env(testScheduler)
        try {
            val current = DwellDiagnosticRecord(DWELL_EMITTED, 2, fenceId = "b")
            env.journal.append(current, DwellDiagnosticOwner.Session(2)) shouldBeEqualTo true
            env.journal.append(record(FIX_RECEIVED, 3), DwellDiagnosticOwner.Session(1)) shouldBeEqualTo false
            env.journal.snapshot(DwellDiagnosticOwner.Session(2)) shouldBeEqualTo listOf(current)
            val prefs = RuntimeEnvironment.getApplication().getSharedPreferences(DwellDiagnosticJournal.PREFS_NAME, Context.MODE_PRIVATE)
            for (malformed in listOf(false, true)) {
                if (malformed) {
                    prefs.edit().putString(DwellDiagnosticJournal.OWNER_KEY, "broken").commit()
                } else {
                    prefs.edit().remove(DwellDiagnosticJournal.OWNER_KEY).commit()
                }
                env.journal.append(record(FIX_RECEIVED, 4), DwellDiagnosticOwner.Session(3)) shouldBeEqualTo true
                env.journal.snapshot(DwellDiagnosticOwner.Session(3)) shouldBeEqualTo listOf(record(FIX_RECEIVED, 4))
            }
        } finally {
            env.scopeJob.cancel()
        }
    }

    private fun record(event: DwellDiagnosticEvent, atElapsedMs: Long) =
        DwellDiagnosticRecord(event = event, atElapsedMs = atElapsedMs)

    private class Env(scheduler: TestCoroutineScheduler, ownerProvider: (() -> Long?)? = null) {
        private val dispatcher = StandardTestDispatcher(scheduler)
        val scopeJob = Job()
        val journal = DwellDiagnosticJournal(
            prefs = lazyOf(
                RuntimeEnvironment.getApplication()
                    .getSharedPreferences(DwellDiagnosticJournal.PREFS_NAME, Context.MODE_PRIVATE)
            ),
            enabled = { true },
            ioContext = dispatcher
        )
        val recorder = DwellDiagnosticRecorder(
            journal = journal,
            enabled = { true },
            scope = CoroutineScope(dispatcher + scopeJob),
            ownerProvider = ownerProvider
        )
    }
}
