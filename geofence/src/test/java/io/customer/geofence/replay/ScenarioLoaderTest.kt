package io.customer.geofence.replay

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test

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
        // A note is carried but never graded.
        scenario.records.size shouldBeEqualTo 4
        scenario.given.single().body.single().name shouldBeEqualTo "Here"
    }

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
        val error = runCatching {
            ScenarioLoader.load(
                scenarioFile(header("no-at"), """{"k":"when","ev":"os.callback","ids":"A","t":"enter"}""")
            )
        }.exceptionOrNull()
        (error is IllegalArgumentException).shouldBeTrue()
    }

    @Test
    fun load_givenUnknownKind_expectRejected() {
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
        val scenario = ScenarioLoader.load(
            scenarioFile(
                header("batch"),
                """{"k":"when","at":0.0,"ev":"os.callback","ids":"A,B,C","n":3,"t":"exit"}"""
            )
        )
        scenario.stimuli.single().geofenceIds() shouldBeEqualTo listOf("A", "B", "C")
    }
}
