package io.customer.geofence.replay

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test

/**
 * The NDJSON reader, on scenarios written here rather than recorded.
 *
 * Pure parsing: no Android, no SDK, no harness. These run everywhere, including where the recorded
 * drives are absent, which is most places.
 */
class ScenarioLoaderTest {

    @Test
    fun load_givenAllFourKinds_expectEachRoutedToItsBucket() {
        val scenario = ScenarioLoader.load(
            scenarioFile(
                header("kinds"),
                """{"k":"when","at":0.0,"ev":"process.start"}""",
                """{"k":"given","at":1.0,"ev":"fixture.api.fetch","ok":true,"n":1,"body":[${fenceAtDevice("A")}]}""",
                """{"k":"then","at":2.0,"ev":"transition.accepted","id":"A","t":"enter","n":1}""",
                """{"k":"note","at":3.0,"ev":"sync.completed","n":1}"""
            )
        )

        scenario.platform shouldBeEqualTo "android"
        scenario.given.size shouldBeEqualTo 1
        scenario.stimuli.size shouldBeEqualTo 1
        scenario.expectations.size shouldBeEqualTo 1
        // A note is carried but never graded — the whole point of the third classification.
        scenario.records.size shouldBeEqualTo 4
        scenario.given.single().body.single().name shouldBeEqualTo "Here"
    }

    /**
     * Provenance must not default to `recorded`.
     *
     * It did, and an authored scenario whose header omitted `source` was then counted as a phone
     * capture — enough on its own to satisfy the "did discovery find any drives?" guard, which is
     * the emptiness check inverted. Reproduced by deleting `source` from an authored scenario and
     * running a one-file corpus: discovery and replay both passed over zero drives.
     */
    @Test
    fun load_givenHeaderWithoutSource_expectUnknownProvenance() {
        val scenario = ScenarioLoader.load(
            scenarioFile(header("no-source"), """{"k":"when","at":0.0,"ev":"process.start"}""")
        )

        scenario.sourceKind shouldBeEqualTo "unknown"
        scenario.isRecorded.shouldBeFalse()
    }

    @Test
    fun load_givenAuthoredSource_expectNotCountedAsADrive() {
        val scenario = ScenarioLoader.load(
            scenarioFile(
                header("authored", sourceKind = "authored"),
                """{"k":"when","at":0.0,"ev":"process.start"}"""
            )
        )

        scenario.isRecorded.shouldBeFalse()
    }

    @Test
    fun load_givenRecordedSource_expectCountedAsADrive() {
        val scenario = ScenarioLoader.load(
            scenarioFile(
                header("recorded", sourceKind = "recorded"),
                """{"k":"when","at":0.0,"ev":"process.start"}"""
            )
        )

        scenario.isRecorded.shouldBeTrue()
    }

    @Test
    fun load_givenRecordWithoutAt_expectRejected() {
        // Defaulting to zero would silently reorder the drive, which is worse than not loading it.
        val error = runCatching {
            ScenarioLoader.load(
                scenarioFile(header("no-at"), """{"k":"when","ev":"os.callback","ids":"A","t":"enter"}""")
            )
        }.exceptionOrNull()
        (error is IllegalArgumentException).shouldBeTrue()
    }

    @Test
    fun load_givenUnknownKind_expectRejected() {
        // Dropping the record instead left the file readable with an input or an assertion missing
        // from it, and the drive still reported green. iOS throws on the same line.
        val error = runCatching {
            ScenarioLoader.load(
                scenarioFile(
                    header("unknown-kind"),
                    """{"k":"when","at":0.0,"ev":"process.start"}""",
                    """{"k":"wehn","at":1.0,"ev":"os.callback","ids":"A","t":"enter"}"""
                )
            )
        }.exceptionOrNull()
        (error is IllegalArgumentException).shouldBeTrue()
        (error?.message?.contains("wehn") == true).shouldBeTrue()
    }

    @Test
    fun load_givenBatchedIds_expectAllFencesRead() {
        // GMS batches several fences onto one broadcast; iOS reports one. The loader reads both.
        val scenario = ScenarioLoader.load(
            scenarioFile(
                header("batch"),
                """{"k":"when","at":0.0,"ev":"os.callback","ids":"A,B,C","n":3,"t":"exit"}"""
            )
        )
        scenario.stimuli.single().geofenceIds() shouldBeEqualTo listOf("A", "B", "C")
    }
}
