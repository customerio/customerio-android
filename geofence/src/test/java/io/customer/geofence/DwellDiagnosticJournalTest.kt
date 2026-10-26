package io.customer.geofence

import android.content.Context
import android.content.SharedPreferences
import io.customer.commontest.core.RobolectricTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.json.JSONArray
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DwellDiagnosticJournalTest : RobolectricTest() {
    private val prefs: SharedPreferences by lazy {
        RuntimeEnvironment.getApplication().getSharedPreferences("dwell_journal_test", Context.MODE_PRIVATE)
    }
    private var enabled = true

    private fun journal(capacity: Int = 3) =
        DwellDiagnosticJournal(lazyOf(prefs), { enabled }, EmptyCoroutineContext, capacity)

    private fun record(atElapsedMs: Long) = DwellDiagnosticRecord(
        event = DwellDiagnosticEvent.DEADLINE_REACHED,
        atElapsedMs = atElapsedMs,
        fenceId = "fence",
        dueElapsedMs = atElapsedMs
    )

    @Test
    fun append_givenExperimentOff_expectNothingWritten() = runTest {
        enabled = false

        journal().append(record(1L)).shouldBeFalse()

        prefs.all.isEmpty().shouldBeTrue()
    }

    @Test
    fun clear_givenExperimentTurnedOff_expectHistoryHiddenThenErased() = runTest {
        journal().append(record(1L)).shouldBeTrue()
        enabled = false

        journal().snapshot() shouldBeEqualTo emptyList<DwellDiagnosticRecord>()
        journal().clear().shouldBeTrue()

        prefs.contains(DwellDiagnosticJournal.KEY).shouldBeFalse()
    }

    @Test
    fun snapshot_givenMoreAppendsThanCapacity_expectNewestPersistedAcrossInstances() = runTest {
        val writer = journal(capacity = 3)
        (1L..5L).forEach { writer.append(record(it)).shouldBeTrue() }

        // Fresh instances hold nothing in memory, so these read what was persisted.
        journal(capacity = 3).snapshot() shouldBeEqualTo (3L..5L).map { record(it) }
        // Reads are bounded too, e.g. after a build that lowered the capacity.
        journal(capacity = 2).snapshot() shouldBeEqualTo (4L..5L).map { record(it) }
    }

    @Test
    fun append_givenUnreadableHistory_expectSnapshotNullThenHistoryReplaced() = runTest {
        val validJson = "{\"event\":\"DEADLINE_REACHED\",\"atElapsedMs\":1}"
        val oversized = "[" + " ".repeat(3 * DwellDiagnosticJournal.MAX_ENCODED_RECORD_CHARS) + validJson + "]"
        val corruptions = listOf<(SharedPreferences.Editor) -> SharedPreferences.Editor>(
            { it.putString(DwellDiagnosticJournal.KEY, "not json") },
            { it.putInt(DwellDiagnosticJournal.KEY, 7) },
            // Valid JSON, but longer than any write: refused before parsing.
            { it.putString(DwellDiagnosticJournal.KEY, oversized) }
        )
        corruptions.forEach { corrupt ->
            corrupt(prefs.edit()).commit()

            journal().snapshot().shouldBeNull()
            journal().append(record(9L)).shouldBeTrue()
            journal().snapshot() shouldBeEqualTo listOf(record(9L))
        }
    }

    @Test
    fun append_givenUnboundedFields_expectOnlyAllowedKeysAndBoundedValues() = runTest {
        journal().append(
            DwellDiagnosticRecord(
                event = DwellDiagnosticEvent.FIX_RECEIVED,
                atElapsedMs = 5L,
                fenceId = "a".repeat(127) + "\uD83D\uDE00" + "b".repeat(400),
                dueElapsedMs = 6L,
                reason = DwellDiagnosticReason.STILL_UNDECIDED,
                priority = "p".repeat(100),
                provider = "fused",
                returnedAccuracyMeters = Double.NaN,
                fixAgeMs = -1L,
                fixElapsedNanos = 7L
            )
        ).shouldBeTrue()

        val stored = JSONArray(prefs.getString(DwellDiagnosticJournal.KEY, null)).getJSONObject(0)
        // Schema guard: any new key, a coordinate say, must be a deliberate change to this set. NaN
        // accuracy and a negative age are dropped rather than failing the append.
        stored.keys().asSequence().toSet() shouldBeEqualTo setOf(
            "event",
            "atElapsedMs",
            "fenceId",
            "dueElapsedMs",
            "reason",
            "priority",
            "provider",
            "fixElapsedNanos"
        )
        // Cut before the surrogate pair, never through it.
        stored.getString("fenceId") shouldBeEqualTo "a".repeat(127)
        stored.getString("priority").length shouldBeEqualTo 32
    }

    @Test
    fun append_givenCancelledWhileReadingOptIn_expectCancellationPropagatesAndLockReleased() = runTest {
        val cancelled = DwellDiagnosticJournal(
            prefs = lazyOf(prefs),
            enabled = { throw CancellationException("caller cancelled") },
            ioContext = EmptyCoroutineContext
        )

        val propagated = try {
            cancelled.append(record(1L))
            false
        } catch (e: CancellationException) {
            true
        }

        // Swallowed, it reads as an opt-out and the cancelled caller carries on.
        propagated.shouldBeTrue()
        prefs.contains(DwellDiagnosticJournal.KEY).shouldBeFalse()
        // The lock is process-wide, so a cancelled call must not hold up later ones.
        journal().append(record(2L)).shouldBeTrue()
    }
}
