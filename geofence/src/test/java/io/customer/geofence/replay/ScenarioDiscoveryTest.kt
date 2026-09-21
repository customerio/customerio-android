package io.customer.geofence.replay

import java.io.File
import java.nio.file.Files
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldThrow
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

    /**
     * An override that cannot be used fails the run instead of emptying it.
     *
     * This is the hole the rest of this class could not see. Every caller here guards on
     * `assumeTrue(isAvailable)`, so an override that resolved to `null` skipped discovery *and*
     * every replay — a typo or a moved checkout looked exactly like a machine that simply has no
     * corpus, and the suite went green having replayed nothing.
     *
     * Exercised through [Scenarios.resolve] rather than the environment: [Scenarios.root] reads
     * `getenv` once and caches it, which a test cannot arrange.
     */
    @Test
    fun resolve_givenOverrideThatDoesNotExist_expectError() {
        invoking {
            Scenarios.resolve(File(Files.createTempDirectory("cio-geofence").toFile(), "nope").path)
        } shouldThrow IllegalStateException::class
    }

    /** The likelier typo: the path exists, but names a file — a drive, say — rather than its directory. */
    @Test
    fun resolve_givenOverrideNamingAFile_expectError() {
        val file = File.createTempFile("drive", ".scenario.ndjson").apply { deleteOnExit() }

        invoking { Scenarios.resolve(file.path) } shouldThrow IllegalStateException::class
    }

    @Test
    fun resolve_givenOverrideThatIsADirectory_expectThatDirectory() {
        val dir = Files.createTempDirectory("cio-geofence").toFile().apply { deleteOnExit() }

        Scenarios.resolve(dir.path) shouldBeEqualTo dir
    }

    /**
     * Blank is not an assertion about where the corpus is, so it falls through to the sibling walk
     * rather than failing. Started somewhere with no checkout above it, so the walk finds nothing
     * and the answer is a plain absence — the case the `assumeTrue` guards are for.
     */
    @Test
    fun resolve_givenBlankOverride_expectSiblingLookup() {
        val empty = Files.createTempDirectory("cio-geofence-empty").toFile().apply { deleteOnExit() }

        Scenarios.resolve("", from = empty).shouldBeNull()
        Scenarios.resolve(null, from = empty).shouldBeNull()
    }
}
