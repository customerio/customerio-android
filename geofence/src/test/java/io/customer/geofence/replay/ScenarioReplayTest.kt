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
import io.customer.geofence.polygon.PolygonFreshFixSource
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
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * Replays recorded drives against the real SDK, with doubles only for Play Services, the network, the
 * clock and event delivery. Skips when the corpus (see [Scenarios]) is absent.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class ScenarioReplayTest(
    private val scenarioName: String,
    private val scenarioFile: File?
) : RobolectricTest() {

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

    private val virtualClock = VirtualClock()

    private val boundaryGate = ReplayBoundaryGate(virtualClock)
    private val api = ReplayApiService(boundaryGate)
    private val registrar = ReplayRegistrar(boundaryGate)
    private val fakeLocationServices = ReplayLocationServices()
    private val replayLogger = ReplayLogger()
    private val identity = ReplayRunner.MutableIdentity()

    // Not `ScopeProviderStub`: the runner needs one shared scheduler to run what a release made runnable.
    private val fakeScopeProvider = ReplayScopeProvider()

    private val fakeSecureUserStore: SecureUserStore = mockk(relaxed = true) {
        every { getUserId() } answers { identity.userId }
    }

    // Delivery is out of scope: the harness grades detection and acceptance, not rows reaching the
    // backend.
    private val scheduler: GeofenceEventScheduler = mockk(relaxed = true)

    // Permission is an OS fact, and every recorded drive was captured with it granted.
    private val permissionChecker: GeofencePermissionChecker = mockk(relaxed = true) {
        every { hasRequiredLocationPermissions() } returns true
        every { isBackgroundDeliveryAvailable() } returns true
    }

    private val replayFreshFix = ReplayPolygonFreshFixSource()

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
                        overrideDependency<PolygonFreshFixSource>(replayFreshFix)
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
        // Disk-backed: a store left by an earlier test in this JVM would seed state the drive never had.
        SDKComponent.android().geofenceRegionStore.clearAll()
        // Separate from the region store; a leftover cooldown would suppress a fence's first crossing.
        SDKComponent.android().geofenceCooldownStore.clearAll()
        SDKComponent.android().pendingGeofenceDeliveryStore.removeAll()
        replayLogger.clear()
    }

    @After
    fun resetDiagnostics() {
        GeofenceDiagnostics.setEnabledForTesting(null)
    }

    private fun foregroundCoordinator() = GeofenceForegroundCoordinator(
        services = SDKComponent.android().geofenceServices,
        secureUserStore = fakeSecureUserStore,
        locationServices = fakeLocationServices,
        regionStore = SDKComponent.android().geofenceRegionStore,
        lastKnownLocation = { fakeLocationServices.lastKnown },
        locationMode = GeofenceLocationMode.AUTOMATIC,
        logger = SDKComponent.geofenceLogger,
        // Unconfined, so `onForeground`'s dispatcher hop runs inline, not on an IO thread the
        // replay outruns.
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
        identity = identity,
        freshFix = replayFreshFix
    )

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
            if (result.unsupported.isNotEmpty()) {
                val evs = result.unsupported.map { it.ev }.distinct().sorted()
                add("  inputs with no seam in this composition: $evs")
            }
            if (result.unanswered.isNotEmpty()) {
                val at = result.unanswered.joinToString { "@%.3f".format(it) }
                add("  recorded fresh fixes no polygon request was waiting for: $at")
            }
            // A scenario that asserts nothing would pass trivially.
            if (report.expectedTotal == 0) add("  scenario carries no expectations")
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
