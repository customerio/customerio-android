package io.customer.geofence.replay

import io.customer.commontest.config.testConfigurationDefault
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * Replays recorded drives against the real SDK, with doubles only for Play Services, the network, the
 * clock and event delivery. Skips when the corpus (see [Scenarios]) is absent.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class ScenarioReplayTest(
    private val scenarioName: String,
    private val scenarioFile: File?
) : ReplayTestSupport() {

    companion object {
        /**
         * An absent corpus yields one skipping placeholder: JUnit errors on a parameterised class with
         * no parameters, which would look like a broken suite.
         */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun drives(): Collection<Array<Any?>> {
            val files = if (Scenarios.isAvailable) Scenarios.replayable() else emptyList()
            if (files.isEmpty()) return listOf(arrayOf("no corpus", null))
            return files.map { arrayOf(it.name.removeSuffix(".scenario.ndjson"), it) }
        }
    }

    @Test
    fun replay_givenRecordedDrive_expectSameDecisions() = runTest {
        val file = scenarioFile
        assumeTrue("geofence-scenarios checkout not present", file != null)
        // A throw (the gate's release-round guard, say) is reported like a mismatch, naming this drive.
        val outcome = try {
            replayOne(file!!)
        } catch (error: Throwable) {
            "$scenarioName:\n  threw while replaying: $error"
        }
        if (outcome != null) throw AssertionError(outcome)
    }

    /** @return a description of what went wrong, or null when the drive replayed faithfully. */
    private suspend fun replayOne(file: File): String? {
        // Starts the drive from clean stores and clean doubles.
        setup(testConfigurationDefault { })
        boundaryGate.reset()
        api.reset()
        registrar.reset()
        fakeLocationServices.reset()
        identity.userId = null
        virtualClock.elapsedSeconds = 0.0
        val scenario = ScenarioLoader.load(file)
        val result = runner().run(scenario)
        val report = ReplayMatcher.compare(scenario, replayLogger.emitted())

        val problems = buildList {
            addAll(result.assertionFailures.map { "  $it" })
            if (result.unsupported.isNotEmpty()) {
                val evs = result.unsupported.map { it.ev }.distinct().sorted()
                add("  inputs with no seam in this composition: $evs")
            }
            if (result.unanswered.isNotEmpty()) {
                val at = result.unanswered.joinToString { "@%.3f".format(it) }
                add("  recorded fresh fixes no polygon request was waiting for: $at")
            }
            // A zero-count checkpoint alone would pass even if no fence was ever registered.
            val hasPositiveCheckpoint = scenario.expectations.any {
                it.ev == ReplayDeliveryAssertions.EVENT && (it.double("count") ?: 0.0) > 0
            }
            if (report.expectedTotal == 0 && !hasPositiveCheckpoint) {
                add("  scenario carries no decision expectations or positive delivery checkpoint")
            }
            // Ahead of the per-decision report: if fetches diverged, every decision saw the wrong
            // catalogue.
            api.fetchAccounting()?.let { add("  $it") }
            if (!report.isMatch) {
                add(report.describe().prependIndent("  "))
                // Nothing matching usually means the replay stopped before deciding; the trace
                // shows where.
                if (report.matched == 0) {
                    val trace = replayLogger.emitted()
                        .groupingBy { "${it.ev}${it.fields["why"]?.let { w -> " why=$w" } ?: ""}" }
                        .eachCount()
                        .entries.sortedByDescending { it.value }
                        .joinToString("\n") { "    ${it.value}x ${it.key}" }
                    add("  nothing matched; what the SDK actually emitted:\n$trace")
                }
            }
        }
        return if (problems.isEmpty()) null else "${file.name}:\n${problems.joinToString("\n")}"
    }
}
