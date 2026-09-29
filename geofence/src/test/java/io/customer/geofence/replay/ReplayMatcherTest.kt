package io.customer.geofence.replay

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.Test

/**
 * The matcher, graded against hand-built emissions.
 *
 * Each case breaks one thing and asserts the matcher notices *that* thing — a green matcher is
 * worthless until it has been shown to go red for the right reason.
 */
class ReplayMatcherTest {

    @Test
    fun compare_givenOnlyNonAssertedRecordsDiffer_expectMatch() {
        val scenario = ScenarioLoader.load(
            scenarioFile(
                header("internals"),
                """{"k":"then","at":1.0,"ev":"transition.accepted","id":"A","t":"enter","n":1}"""
            )
        )
        val emitted = listOf(
            EmittedRecord("transition.accepted", "out", mapOf("id" to "A", "t" to "enter", "n" to "1")),
            // Internal steps must not affect the verdict, no matter how many the SDK emits.
            EmittedRecord("sync.completed", "obs", mapOf("n" to "3")),
            EmittedRecord("rank.evaluated", "obs", mapOf("n" to "2"))
        )
        ReplayMatcher.compare(scenario, emitted).isMatch.shouldBeTrue()
    }

    @Test
    fun compare_givenDecisionForWrongFence_expectMismatch() {
        val scenario = ScenarioLoader.load(
            scenarioFile(
                header("wrong-fence"),
                """{"k":"then","at":1.0,"ev":"transition.accepted","id":"A","t":"enter","n":1}"""
            )
        )
        val report = ReplayMatcher.compare(
            scenario,
            listOf(EmittedRecord("transition.accepted", "out", mapOf("id" to "B", "t" to "enter")))
        )
        report.isMatch shouldBeEqualTo false
        report.mismatches.size shouldBeEqualTo 2
        report.describe().contains("never happened").shouldBeTrue()
    }

    @Test
    fun compare_givenInternalDecisionExpectations_expectThemIgnored() {
        // A scenario generated before 2026-09-11 can still carry `then` records for the SDK's
        // decisions about its own inputs. They are not outputs, so they grade nothing — an SDK that
        // deduplicates somewhere else entirely must still pass a drive.
        val scenario = ScenarioLoader.load(
            scenarioFile(
                header("why"),
                """{"k":"then","at":1.0,"ev":"transition.dropped","id":"A","t":"exit","why":"never_entered"}""",
                """{"k":"then","at":1.1,"ev":"transition.accepted","id":"B","t":"enter"}"""
            )
        )
        val report = ReplayMatcher.compare(
            scenario,
            listOf(EmittedRecord("transition.accepted", "out", mapOf("id" to "B", "t" to "enter")))
        )
        report.isMatch shouldBeEqualTo true
        report.expectedTotal shouldBeEqualTo 1
    }
}
