package io.customer.geofence.replay

import java.io.File
import java.nio.file.Files
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldThrow
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Checked once for the whole corpus, not per drive in the parameterised [ScenarioReplayTest]. */
class ScenarioDiscoveryTest {

    @Test
    fun discover_givenScenarioFilesOnDisk_expectEveryOneReadable() {
        assumeTrue("geofence-scenarios checkout not present", Scenarios.isAvailable)

        // Count read from this pass: another discovery call would clear `unreadable`.
        Scenarios.replayable()
        val recordedDrives = Scenarios.recordedCount

        // Before the emptiness check, whose message would hide an unreadable corpus's parse errors.
        if (Scenarios.unreadable.isNotEmpty()) {
            throw AssertionError(
                "${Scenarios.unreadable.size} scenario file(s) could not be read and were dropped " +
                    "from the run:\n" + Scenarios.unreadable.joinToString("\n") { "  - $it" }
            )
        }
        // A root with no drives (an override one level too high, say) passes the check above.
        if (recordedDrives == 0) {
            throw AssertionError(
                "the corpus at ${Scenarios.root} holds no recorded drive — check the path points " +
                    "at the directory containing the .scenario.ndjson files"
            )
        }
    }

    /**
     * Exercised through [Scenarios.resolve] because [Scenarios.root] reads `getenv` once and caches
     * it.
     */
    @Test
    fun resolve_givenOverrideThatDoesNotExist_expectError() {
        invoking {
            Scenarios.resolve(File(Files.createTempDirectory("cio-geofence").toFile(), "nope").path)
        } shouldThrow IllegalStateException::class
    }

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

    /** Started where no checkout sits above, so the sibling walk finds nothing. */
    @Test
    fun resolve_givenBlankOverride_expectSiblingLookup() {
        val empty = Files.createTempDirectory("cio-geofence-empty").toFile().apply { deleteOnExit() }

        Scenarios.resolve("", from = empty).shouldBeNull()
        Scenarios.resolve(null, from = empty).shouldBeNull()
    }
}
