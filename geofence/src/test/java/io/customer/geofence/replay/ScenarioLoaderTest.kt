package io.customer.geofence.replay

import org.amshove.kluent.shouldBeEqualTo
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
