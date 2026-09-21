package io.customer.geofence.replay

import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Discovery, checked once for the whole corpus.
 *
 * Separate from [ScenarioReplayTest] because that class is parameterised per drive, and a check
 * about the corpus as a whole would otherwise re-run — and re-report — once for every file in it.
 */
class ScenarioDiscoveryTest {

    /**
     * Every scenario file on disk was readable.
     *
     * Discovery drops what it cannot parse, and without this that drop is invisible: the suite
     * reports a clean pass over the drives it *could* read and says nothing about the ones it
     * could not. Asserted on `isAvailable` alone, so a corpus that is present but entirely
     * unreadable fails here rather than skipping.
     */
    @Test
    fun discover_givenScenarioFilesOnDisk_expectEveryOneReadable() {
        assumeTrue("geofence-scenarios checkout not present", Scenarios.isAvailable)

        // One discovery pass. Calling `recorded()` again after `replayable()` would clear
        // `unreadable` and drop the conformance failures the pass had already collected, so the
        // recorded count is read from the pass rather than recomputed.
        Scenarios.replayable()
        val recordedDrives = Scenarios.recordedCount

        // Unreadable files are reported before the emptiness check. The other order hides them:
        // a corpus where *every* file fails to parse is also a corpus with no drives, and the
        // path message would be thrown first while the parse errors went unmentioned.
        if (Scenarios.unreadable.isNotEmpty()) {
            throw AssertionError(
                "${Scenarios.unreadable.size} scenario file(s) could not be read and were dropped " +
                    "from the run:\n" + Scenarios.unreadable.joinToString("\n") { "  - $it" }
            )
        }
        // A root that exists but holds no drives is the failure this test is for, and asserting
        // only on `unreadable` misses it. Point the override one level too high — at
        // `geofence-scenarios` rather than `geofence-scenarios/recorded`, the exact mistake
        // `Scenarios`' own KDoc warns about — and everything below passes over zero drives.
        if (recordedDrives == 0) {
            throw AssertionError(
                "the corpus at ${Scenarios.root} holds no recorded drive — check the path points " +
                    "at the directory containing the .scenario.ndjson files"
            )
        }
    }
}
