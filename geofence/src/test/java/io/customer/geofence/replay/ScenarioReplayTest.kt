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
import io.customer.geofence.di.geofenceCooldownStore
import io.customer.geofence.di.geofenceCrossingPipeline
import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.di.geofenceManager
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
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Replays recorded drives against the real SDK.
 *
 * The composition below substitutes four things and nothing else: Play Services, the network, the
 * wall clock, and event delivery. Ranking, the registration plan, containment bookkeeping, the
 * cooldown, the anonymous drop and movement routing all run for real — which is only possible
 * because those decisions were lifted out of `GeofenceBroadcastReceiver` and `GeofenceManager`.
 * Before that, replacing GMS replaced them too, and a passing replay would have proved nothing.
 *
 * The drives live in a separate private checkout; see [Scenarios]. These tests **skip**, not pass,
 * when it is absent.
 */
@RunWith(RobolectricTestRunner::class)
class ScenarioReplayTest : RobolectricTest() {

    private val virtualClock = VirtualClock()

    // The network and Play Services answer on the drive's clock rather than instantly, so an input
    // recorded mid-sync is replayed mid-sync. See `ReplayBoundaryGate`.
    private val boundaryGate = ReplayBoundaryGate(virtualClock)
    private val api = ReplayApiService(boundaryGate)
    private val registrar = ReplayRegistrar(boundaryGate)
    private val fakeLocationServices = ReplayLocationServices()
    private val replayLogger = ReplayLogger()
    private val identity = ReplayRunner.MutableIdentity()

    // Not `ScopeProviderStub`: its scopes each carry their own scheduler, and once a double can
    // park the runner needs one it can name to run what a release made runnable.
    private val fakeScopeProvider = ReplayScopeProvider()

    private val fakeSecureUserStore: SecureUserStore = mockk(relaxed = true) {
        every { getUserId() } answers { identity.userId }
    }

    // Delivery is out of scope: the harness asserts the SDK detected and accepted a crossing, not
    // that a row reached the backend. A drive can be fully correct on an offline phone.
    private val scheduler: GeofenceEventScheduler = mockk(relaxed = true)

    // Permission is an OS fact, and every recorded drive was captured with it granted.
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
            "GeofenceRegistrar" to (SDKComponent.android().geofenceManager to registrar)
        )
        // A real, disk-backed store carried over from an earlier test in this JVM would hand the
        // replay a catalogue and a containment set the drive never established.
        SDKComponent.android().geofenceRegionStore.clearAll()
        // A separate store, and the region store's clearAll does not touch it. Left over, a
        // previous drive's cooldown suppresses this one's first crossing at every shared fence —
        // which reads as an SDK behaviour difference rather than the test-isolation bug it is.
        SDKComponent.android().geofenceCooldownStore.clearAll()
        SDKComponent.android().pendingGeofenceDeliveryStore.removeAll()
        replayLogger.clear()
    }

    @After
    fun resetDiagnostics() {
        GeofenceDiagnostics.setEnabledForTesting(null)
    }

    /**
     * The real foreground coordinator, built from the same doubles the rest of the composition
     * uses. Not a stand-in for its decisions — replay says the app came forward, the SDK decides
     * what that means.
     */
    private fun foregroundCoordinator() = GeofenceForegroundCoordinator(
        services = SDKComponent.android().geofenceServices,
        secureUserStore = fakeSecureUserStore,
        locationServices = fakeLocationServices,
        regionStore = SDKComponent.android().geofenceRegionStore,
        lastKnownLocation = { fakeLocationServices.lastKnown },
        locationMode = GeofenceLocationMode.AUTOMATIC,
        logger = SDKComponent.geofenceLogger,
        // Unconfined, so `onForeground`'s dispatcher hop resolves on the test scheduler rather
        // than parking work on a real IO thread the replay would run straight past.
        dispatchers = DispatchersProviderStub()
    )

    private fun runner() = ReplayRunner(
        gate = boundaryGate,
        scheduler = fakeScopeProvider.scheduler,
        geofenceScope = fakeScopeProvider.geofenceScope,
        api = api,
        registrar = registrar,
        pipeline = SDKComponent.android().geofenceCrossingPipeline,
        services = SDKComponent.android().geofenceServices,
        foreground = foregroundCoordinator(),
        identity = identity
    )

    @Test
    fun replay_givenRecordedDrives_expectSameDecisions() = runTest {
        assumeTrue("geofence-scenarios checkout not present", Scenarios.isAvailable)
        val files = Scenarios.replayable()
        assumeTrue("no android scenarios found in ${Scenarios.root}", files.isNotEmpty())

        val failures = mutableListOf<String>()
        for (file in files) {
            val outcome = replayOne(file)
            if (outcome != null) failures.add(outcome)
        }
        if (failures.isNotEmpty()) {
            throw AssertionError(failures.joinToString("\n\n"))
        }
    }

    /** @return a description of what went wrong, or null when the drive replayed faithfully. */
    private suspend fun replayOne(file: File): String? {
        // Each drive is its own world. `setup` rebuilds the graph and wipes the stores, but the
        // doubles are fields of this test class and would otherwise carry the previous drive's
        // fetch queue, registered set, identity and clock into the next one.
        setup(testConfigurationDefault { })
        // A boundary the previous drive left parked would resume inside this one, mid-scenario.
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
            if (result.unsupported.isNotEmpty()) {
                val evs = result.unsupported.map { it.ev }.distinct().sorted()
                add("  inputs with no seam in this composition: $evs")
            }
            // A scenario that asserts nothing passes trivially. Whether a capture is a real drive is
            // a human call, but "decided something at all" is the floor a machine can hold.
            if (report.expectedTotal == 0) add("  scenario carries no expectations")
            // Ahead of the per-decision report: when this is off, everything downstream of it is
            // downstream of the wrong catalogue, and reading the mismatch list is wasted effort.
            api.fetchAccounting()?.let { add("  $it") }
            if (!report.isMatch) {
                add(report.describe().prependIndent("  "))
                // Nothing matching at all almost never means "every decision was wrong" — it means
                // the replay never got as far as deciding. The internal records say where it
                // stopped, and without them the report above is a wall of "never happened".
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
