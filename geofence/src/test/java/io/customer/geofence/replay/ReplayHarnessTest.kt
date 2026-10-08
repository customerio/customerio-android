package io.customer.geofence.replay

import io.customer.geofence.di.pendingGeofenceDeliveryStore
import io.customer.sdk.core.di.SDKComponent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Drives the real SDK through the replay composition on hand-written scenarios. The corpus lives
 * outside the repo, so this is what covers the composition on CI.
 */
@RunWith(RobolectricTestRunner::class)
class ReplayHarnessTest : ReplayTestSupport() {

    @Test
    fun replay_givenPendingRefreshAtProcessDeath_expectLaterFixDoesNotSpendDeadIntent() = runTest {
        val scenario = ScenarioLoader.load(
            scenarioFile(
                header("relaunch-pending-refresh"),
                """{"k":"when","at":0,"ev":"process.start"}""",
                """{"k":"when","at":0.1,"ev":"module.init","launch":"app_start"}""",
                """{"k":"when","at":1,"ev":"identity.changed","ok":true}""",
                """{"k":"when","at":1.5,"ev":"location.fix","lat":10.0151,"lon":20,"prov":"bus"}""",
                """{"k":"given","at":2,"ev":"fixture.api.fetch","ok":true,"body":[${fenceAtDevice("A")}]}""",
                // The host requested a live fix, but this process died before it arrived.
                """{"k":"when","at":3,"ev":"app.foreground"}""",
                """{"k":"when","at":4,"ev":"process.start"}""",
                """{"k":"when","at":4.1,"ev":"module.init","launch":"app_start"}""",
                """{"k":"when","at":5,"ev":"location.fix","lat":10,"lon":20,"prov":"bus"}"""
            )
        )

        val result = runner().run(scenario)

        result.unsupported shouldBeEqualTo emptyList()
        api.fetchAccounting() shouldBeEqualTo null
        // One initial identify request and one host foreground request, with no relaunch retry.
        fakeLocationServices.silentRequestCount shouldBeEqualTo 2
        replayLogger.emitted().count { it.ev == "registration.applied" } shouldBeEqualTo 1
    }

    @Test
    fun replay_givenIdentifyAfterRelaunch_expectPersistedAnchorNotInventLiveFixIntent() = runTest {
        val scenario = ScenarioLoader.load(
            scenarioFile(
                header("relaunch-identify-anchor"),
                """{"k":"when","at":0,"ev":"process.start"}""",
                """{"k":"when","at":0.1,"ev":"module.init","launch":"app_start"}""",
                """{"k":"when","at":1,"ev":"identity.changed","ok":true}""",
                """{"k":"when","at":1.5,"ev":"location.fix","lat":10.0151,"lon":20,"prov":"bus"}""",
                """{"k":"given","at":2,"ev":"fixture.api.fetch","ok":true,"body":[${fenceAtDevice("A")}]}""",
                """{"k":"when","at":4,"ev":"process.start"}""",
                """{"k":"when","at":4.1,"ev":"module.init","launch":"app_start"}""",
                """{"k":"when","at":4.2,"ev":"identity.changed","ok":true}""",
                // Identify uses the persisted registration anchor. An unsolicited bus fix does not
                // create a refresh request merely because the process relaunched.
                """{"k":"when","at":5,"ev":"location.fix","lat":10,"lon":20,"prov":"bus"}"""
            )
        )

        val result = runner().run(scenario)

        result.unsupported shouldBeEqualTo emptyList()
        api.fetchAccounting() shouldBeEqualTo null
        replayLogger.emitted().count { it.ev == "registration.applied" } shouldBeEqualTo 1
    }

    @Test
    fun replay_givenProcessRelaunchAndBootRestore_expectCachedRegionsAndQueuedTransitionPreserved() = runTest {
        val scenario = ScenarioLoader.load(
            scenarioFile(
                header("relaunch-boot-restore"),
                """{"k":"when","at":0,"ev":"process.start"}""",
                """{"k":"when","at":0.1,"ev":"module.init","launch":"app_start"}""",
                """{"k":"when","at":1,"ev":"identity.changed","ok":true}""",
                """{"k":"when","at":1.5,"ev":"location.fix","lat":10.0151,"lon":20,"prov":"bus"}""",
                """{"k":"given","at":2,"ev":"fixture.api.fetch","ok":true,"body":[${fenceAtDevice("A")},${fenceFarAway("B")}]}""",
                """{"k":"when","at":7,"ev":"os.callback","ids":"A","t":"enter","lat":10,"lon":20,"acc":10,"age":0}""",
                """{"k":"when","at":10,"ev":"process.start"}""",
                """{"k":"when","at":10.1,"ev":"module.init","launch":"app_start"}""",
                """{"k":"when","at":10.2,"ev":"module.wake","launch":"boot_restore"}"""
            )
        )

        val result = runner().run(scenario)

        result.unsupported shouldBeEqualTo emptyList()
        api.fetchAccounting() shouldBeEqualTo null
        api.fetchCount shouldBeEqualTo 1
        val registrations = replayLogger.emitted().filter { it.ev == "registration.applied" }
        registrations.map { it.geofenceIds() } shouldBeEqualTo listOf(listOf("A", "B"), listOf("A", "B"))
        registrar.registeredIds shouldBeEqualTo linkedSetOf("A", "B", "cio_movement_trigger")
        val queued = SDKComponent.android().pendingGeofenceDeliveryStore.loadAll()
        queued.map { it.geofenceId } shouldBeEqualTo listOf("A")
    }

    @Test
    fun replay_givenFetchAndIdentify_expectTheFetchedSetRegistered() = runTest {
        val scenario = ScenarioLoader.load(
            scenarioFile(
                header("cold-start"),
                """{"k":"when","at":0.0,"ev":"process.start"}""",
                """{"k":"when","at":0.1,"ev":"module.init","launch":"app_start"}""",
                // Identify before the fix, as captures record it; the `prov=bus` fix then drives
                // the sync.
                """{"k":"when","at":1.0,"ev":"identity.changed","ok":true}""",
                """{"k":"when","at":1.5,"ev":"location.fix","lat":10.00000,"lon":20.00000,"prov":"bus"}""",
                """{"k":"given","at":2.0,"ev":"fixture.api.fetch","ok":true,"n":2,"body":[${fenceAtDevice("A")},${fenceFarAway("B")}]}"""
            )
        )

        val result = runner().run(scenario)

        result.unsupported shouldBeEqualTo emptyList()
        api.fetchCount shouldBeEqualTo 1
        api.starvedFetchCount shouldBeEqualTo 0
        registrar.registeredIds shouldBeEqualTo linkedSetOf("A", "B", "cio_movement_trigger")
        val applied = replayLogger.emitted().single { it.ev == "registration.applied" }
        applied.geofenceIds() shouldBeEqualTo listOf("A", "B")
    }

    @Test
    fun replay_givenCrossingIntoRegisteredFence_expectAcceptedForThatFence() = runTest {
        val scenario = ScenarioLoader.load(
            scenarioFile(
                header("crossing"),
                """{"k":"when","at":1.0,"ev":"identity.changed","ok":true}""",
                """{"k":"when","at":1.5,"ev":"location.fix","lat":10.00000,"lon":20.00000,"prov":"bus"}""",
                """{"k":"given","at":2.0,"ev":"fixture.api.fetch","ok":true,"n":1,"body":[${fenceFarAway("B")}]}""",
                // Arriving at B, which the device was outside of at registration time.
                """{"k":"when","at":60.0,"ev":"os.callback","ids":"B","n":1,"t":"enter","lat":10.04510,"lon":20.00000}"""
            )
        )

        runner().run(scenario)

        val accepted = replayLogger.emitted().filter { it.ev == "transition.accepted" }
        accepted.map { it.fields["id"] } shouldBeEqualTo listOf("B")
        accepted.single().fields["t"] shouldBeEqualTo "enter"
    }

    @Test
    fun replay_givenAnonymousSession_expectCrossingDroppedNotAccepted() = runTest {
        val scenario = ScenarioLoader.load(
            scenarioFile(
                header("anonymous"),
                // In the other order no sync runs, and the drop below would pass on `unknown_id`.
                """{"k":"when","at":1.0,"ev":"identity.changed","ok":true}""",
                """{"k":"when","at":1.5,"ev":"location.fix","lat":10.00000,"lon":20.00000,"prov":"bus"}""",
                """{"k":"given","at":2.0,"ev":"fixture.api.fetch","ok":true,"n":1,"body":[${fenceFarAway("B")}]}""",
                """{"k":"when","at":30.0,"ev":"identity.changed","ok":false}""",
                """{"k":"when","at":60.0,"ev":"os.callback","ids":"B","n":1,"t":"enter","lat":10.04510,"lon":20.00000}"""
            )
        )

        runner().run(scenario)

        val emitted = replayLogger.emitted()
        // Precondition: B was registered, so the refusal is not an empty store refusing everything.
        api.fetchCount shouldBeEqualTo 1
        api.unusedFixtureCount shouldBeEqualTo 0
        emitted.count { it.ev == "registration.applied" } shouldBeEqualTo 1
        registrar.calls.any { it.startsWith("replace(") }.shouldBeTrue()

        emitted.none { it.ev == "transition.accepted" }.shouldBeTrue()
        // Sign-out clears the registered set, so B is refused as an orphan before the identity check.
        emitted.any { it.ev == "transition.dropped" && it.fields["id"] == "B" }.shouldBeTrue()

        // The only CI-visible coverage of the asserted `module.reset` record, which sign-out emits.
        emitted.count { it.ev == "module.reset" } shouldBeEqualTo 1
        registrar.calls.any { it == "clearAll" }.shouldBeTrue()
    }

    @Test
    fun replay_givenCrossingForUnregisteredFence_expectDroppedAndRemovedFromOs() = runTest {
        val scenario = ScenarioLoader.load(
            scenarioFile(
                header("orphan"),
                """{"k":"when","at":1.0,"ev":"identity.changed","ok":true}""",
                """{"k":"when","at":1.5,"ev":"location.fix","lat":10.00000,"lon":20.00000,"prov":"bus"}""",
                """{"k":"given","at":2.0,"ev":"fixture.api.fetch","ok":true,"n":1,"body":[${fenceAtDevice("A")}]}""",
                """{"k":"when","at":60.0,"ev":"os.callback","ids":"GHOST","n":1,"t":"enter","lat":10.00000,"lon":20.00000}"""
            )
        )

        runner().run(scenario)

        // Precondition: A is registered.
        api.fetchCount shouldBeEqualTo 1
        registrar.registeredIds.contains("A").shouldBeTrue()

        val dropped = replayLogger.emitted().filter { it.ev == "transition.dropped" }
        dropped.any { it.fields["id"] == "GHOST" && it.fields["why"] == "unknown_id" }.shouldBeTrue()
        registrar.calls.any { it == "remove(GHOST)" }.shouldBeTrue()
    }

    @Test
    fun replay_givenUnknownStimulus_expectReportedNotSilentlyIgnored() = runTest {
        val scenario = ScenarioLoader.load(
            scenarioFile(
                header("unknown-input"),
                """{"k":"when","at":0.0,"ev":"os.something.new","id":"A"}""",
                """{"k":"when","at":1.0,"ev":"module.init","launch":"unsupported_init"}""",
                """{"k":"when","at":2.0,"ev":"module.wake","launch":"unsupported_wake"}"""
            )
        )

        val result = runner().run(scenario)

        result.unsupported.map { it.ev } shouldBeEqualTo listOf("os.something.new", "module.init", "module.wake")
    }
}
