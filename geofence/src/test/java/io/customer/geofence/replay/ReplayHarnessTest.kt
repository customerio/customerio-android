package io.customer.geofence.replay

import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.commontest.util.DispatchersProviderStub
import io.customer.geofence.GeofenceDiagnostics
import io.customer.geofence.GeofenceForegroundCoordinator
import io.customer.geofence.GeofenceLocationMode
import io.customer.geofence.GeofencePermissionChecker
import io.customer.geofence.GeofenceRegistrar
import io.customer.geofence.api.GeofenceApiService
import io.customer.geofence.di.geofenceApiService
import io.customer.geofence.di.geofenceCooldownStore
import io.customer.geofence.di.geofenceCrossingPipeline
import io.customer.geofence.di.geofenceEventScheduler
import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.di.geofenceManager
import io.customer.geofence.di.geofencePermissionChecker
import io.customer.geofence.di.geofenceRegionStore
import io.customer.geofence.di.geofenceServices
import io.customer.geofence.di.pendingGeofenceDeliveryStore
import io.customer.geofence.worker.GeofenceEventScheduler
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.clock
import io.customer.sdk.core.util.Clock
import io.customer.sdk.core.util.Logger
import io.customer.sdk.core.util.ScopeProvider
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Drives the real SDK through the replay composition on hand-written scenarios.
 *
 * The recorded corpus lives outside the repo, so this is what covers the composition on CI: a
 * harness that silently stops driving the SDK would report every scenario as a pass. Expectations
 * come from the contract, not from observed output; only the distances between coordinates matter.
 */
@RunWith(RobolectricTestRunner::class)
class ReplayHarnessTest : RobolectricTest() {

    private val virtualClock = VirtualClock()

    // Same execution model as ScenarioReplayTest, so this self-test covers the harness that suite runs.
    private val boundaryGate = ReplayBoundaryGate(virtualClock)
    private val api = ReplayApiService(boundaryGate)
    private val registrar = ReplayRegistrar(boundaryGate)
    private val fakeLocationServices = ReplayLocationServices()
    private val replayLogger = ReplayLogger()
    private val identity = ReplayRunner.MutableIdentity()
    private val fakeScopeProvider = ReplayScopeProvider()

    private val fakeSecureUserStore: SecureUserStore = mockk(relaxed = true) {
        every { getUserId() } answers { identity.userId }
    }
    private val scheduler: GeofenceEventScheduler = mockk(relaxed = true)
    private val permissionChecker: GeofencePermissionChecker = mockk(relaxed = true) {
        every { hasRequiredLocationPermissions() } returns true
        every { isBackgroundDeliveryAvailable() } returns true
    }

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
                diGraph {
                    sdk {
                        overrideDependency<Clock>(virtualClock)
                        overrideDependency<Logger>(replayLogger)
                        overrideDependency<ScopeProvider>(fakeScopeProvider)
                        overrideDependency<GeofenceApiService>(api)
                    }
                    android {
                        overrideDependency<GeofenceRegistrar>(registrar)
                        overrideDependency<GeofenceEventScheduler>(scheduler)
                        overrideDependency<GeofencePermissionChecker>(permissionChecker)
                        overrideDependency<SecureUserStore>(fakeSecureUserStore)
                    }
                }
            }
        )
        GeofenceDiagnostics.setEnabledForTesting(true)
        assertComposedWith(
            "SecureUserStore" to (SDKComponent.android().secureUserStore to fakeSecureUserStore),
            "ScopeProvider" to (SDKComponent.scopeProvider to fakeScopeProvider),
            "Clock" to (SDKComponent.clock to virtualClock),
            "Logger" to (SDKComponent.logger to replayLogger),
            "GeofenceRegistrar" to (SDKComponent.android().geofenceManager to registrar),
            "GeofenceApiService" to (SDKComponent.geofenceApiService to api),
            "GeofenceEventScheduler" to (SDKComponent.android().geofenceEventScheduler to scheduler),
            "GeofencePermissionChecker" to (SDKComponent.android().geofencePermissionChecker to permissionChecker)
        )
        SDKComponent.android().geofenceRegionStore.clearAll()
        // Separate from the region store; a leftover cooldown would suppress the first crossing at
        // any fence an earlier drive crossed.
        SDKComponent.android().geofenceCooldownStore.clearAll()
        SDKComponent.android().pendingGeofenceDeliveryStore.removeAll()
        replayLogger.clear()
    }

    @After
    fun resetDiagnostics() {
        GeofenceDiagnostics.setEnabledForTesting(null)
    }

    private fun runner() = ReplayRunner(
        gate = boundaryGate,
        scheduler = fakeScopeProvider.scheduler,
        geofenceScope = fakeScopeProvider.geofenceScope,
        api = api,
        registrar = registrar,
        pipeline = SDKComponent.android().geofenceCrossingPipeline,
        services = SDKComponent.android().geofenceServices,
        foreground = foregroundCoordinator(),
        identity = identity,
        // Not wired into the graph: these scenarios never drive the polygon fresh-fix path.
        freshFix = ReplayPolygonFreshFixSource()
    )

    /**
     * The real foreground coordinator on the composition's doubles: replay says the app came
     * forward, the SDK decides what that means.
     */
    private fun foregroundCoordinator() = GeofenceForegroundCoordinator(
        services = SDKComponent.android().geofenceServices,
        secureUserStore = fakeSecureUserStore,
        locationServices = fakeLocationServices,
        regionStore = SDKComponent.android().geofenceRegionStore,
        lastKnownLocation = { fakeLocationServices.lastKnown },
        locationMode = GeofenceLocationMode.AUTOMATIC,
        logger = SDKComponent.geofenceLogger,
        // Unconfined, so `onForeground`'s dispatcher hop runs inline rather than on a real IO
        // thread the replay would run straight past.
        dispatchers = DispatchersProviderStub()
    )

    @Test
    fun replay_givenFetchAndIdentify_expectTheFetchedSetRegistered() = runTest {
        // Asserts the outward OS call, not SDK internals: both fences plus the movement trigger.
        val scenario = ScenarioLoader.load(
            scenarioFile(
                header("cold-start"),
                """{"k":"when","at":0.0,"ev":"process.start"}""",
                """{"k":"when","at":0.1,"ev":"module.init","launch":"app_start"}""",
                // Identify before the fix, as captures record it: identify carries no position, so
                // the SDK arms and the `prov=bus` fix drives the sync.
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
        // The backend rejects anonymous geofence tracks, so the SDK must refuse before queueing.
        val scenario = ScenarioLoader.load(
            scenarioFile(
                header("anonymous"),
                // Identify before the fix: in the other order no sync runs, B is never registered,
                // and the drop below would pass on `unknown_id` without exercising sign-out.
                """{"k":"when","at":1.0,"ev":"identity.changed","ok":true}""",
                """{"k":"when","at":1.5,"ev":"location.fix","lat":10.00000,"lon":20.00000,"prov":"bus"}""",
                """{"k":"given","at":2.0,"ev":"fixture.api.fetch","ok":true,"n":1,"body":[${fenceFarAway("B")}]}""",
                """{"k":"when","at":30.0,"ev":"identity.changed","ok":false}""",
                """{"k":"when","at":60.0,"ev":"os.callback","ids":"B","n":1,"t":"enter","lat":10.04510,"lon":20.00000}"""
            )
        )

        runner().run(scenario)

        val emitted = replayLogger.emitted()
        // Precondition: B was registered, so the refusal below is not an empty store refusing everything.
        api.fetchCount shouldBeEqualTo 1
        api.unusedFixtureCount shouldBeEqualTo 0
        emitted.count { it.ev == "registration.applied" } shouldBeEqualTo 1
        registrar.calls.any { it.startsWith("replace(") }.shouldBeTrue()

        emitted.none { it.ev == "transition.accepted" }.shouldBeTrue()
        // Sign-out clears the registered set, so the crossing is refused as an orphan before the
        // identity check. Pinned to B so a drop for another reason cannot stand in for it.
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
                // Identify before the fix, as in the anonymous case: the other order registers
                // nothing, and every id would be unknown.
                """{"k":"when","at":1.0,"ev":"identity.changed","ok":true}""",
                """{"k":"when","at":1.5,"ev":"location.fix","lat":10.00000,"lon":20.00000,"prov":"bus"}""",
                """{"k":"given","at":2.0,"ev":"fixture.api.fetch","ok":true,"n":1,"body":[${fenceAtDevice("A")}]}""",
                """{"k":"when","at":60.0,"ev":"os.callback","ids":"GHOST","n":1,"t":"enter","lat":10.00000,"lon":20.00000}"""
            )
        )

        runner().run(scenario)

        // A is registered, so rejecting GHOST is the SDK telling them apart, not an empty store.
        api.fetchCount shouldBeEqualTo 1
        registrar.registeredIds.contains("A").shouldBeTrue()

        val dropped = replayLogger.emitted().filter { it.ev == "transition.dropped" }
        dropped.any { it.fields["id"] == "GHOST" && it.fields["why"] == "unknown_id" }.shouldBeTrue()
        // Self-heal: a fence the SDK does not know about is torn down at the OS so it stops firing.
        registrar.calls.any { it == "remove(GHOST)" }.shouldBeTrue()
    }

    @Test
    fun replay_givenUnknownStimulus_expectReportedNotSilentlyIgnored() = runTest {
        // A capture carrying an input the composition cannot drive must fail loudly. Ignoring it
        // would let the harness quietly stop exercising a whole channel.
        val scenario = ScenarioLoader.load(
            scenarioFile(
                header("unknown-input"),
                """{"k":"when","at":0.0,"ev":"os.something.new","id":"A"}"""
            )
        )

        val result = runner().run(scenario)

        result.unsupported.map { it.ev } shouldBeEqualTo listOf("os.something.new")
    }
}
