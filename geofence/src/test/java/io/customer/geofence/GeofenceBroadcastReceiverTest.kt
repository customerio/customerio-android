package io.customer.geofence

import android.location.Location
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.di.pendingGeofenceDeliveryStore
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.worker.GeofenceEventScheduler
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.core.util.Clock
import io.customer.sdk.core.util.Logger
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceBroadcastReceiverTest : RobolectricTest() {

    // 'mock' prefix to avoid shadowing SDKComponent.eventBus / AndroidSDKComponent
    // extension properties inside `sdk { ... }` / `android { ... }` override lambdas.
    private val mockEventBus: EventBus = mockk(relaxed = true)
    private val mockScheduler: GeofenceEventScheduler = mockk(relaxed = true)
    private val mockServices: GeofenceServices = mockk(relaxed = true)
    private val mockCooldownFilter: GeofenceCooldownFilter = mockk(relaxed = true)
    private val mockStore: GeofenceRegionStore = mockk(relaxed = true)
    private val mockManager: GeofenceRegistrar = mockk(relaxed = true)
    private val mockSecureUserStore: SecureUserStore = mockk(relaxed = true)

    /**
     * Captures what the SDK actually wrote. The geofence logger is a computed singleton over the
     * SDK `Logger`, so it cannot be mocked directly — and asserting the emitted record is the
     * stronger check anyway: it covers the `ev=` a parser dispatches on, not merely that some
     * method was called.
     */
    private class CapturingLogger : Logger {
        val messages = mutableListOf<String>()
        override var logLevel: CioLogLevel = CioLogLevel.DEBUG
        override fun setLogDispatcher(dispatcher: ((CioLogLevel, String) -> Unit)?) = Unit
        override fun info(message: String, tag: String?) { messages.add(message) }
        override fun debug(message: String, tag: String?) { messages.add(message) }
        override fun error(message: String, tag: String?, throwable: Throwable?) { messages.add(message) }
    }

    private val capturingLogger = CapturingLogger()

    @After
    fun resetDiagnostics() {
        // null, not false: null restores the manifest value, false pins the gate off for every
        // later test class in this JVM.
        GeofenceDiagnostics.setEnabledForTesting(null)
    }

    /**
     * The point of these records: a broadcast the SDK woke for and could make nothing of must
     * leave a trace, or the capture is byte-identical to a process the OS never talked to.
     * Asserted on the emitted tail so deleting the log line fails the test.
     */
    private fun expectRecorded(ev: String, why: String) {
        val match = capturingLogger.messages.firstOrNull { it.contains("ev=$ev") && it.contains("why=$why") }
        if (match == null) {
            throw AssertionError(
                "expected a record with ev=$ev why=$why; captured:\n" +
                    capturingLogger.messages.joinToString("\n  ", prefix = "  ")
            )
        }
    }

    // Real-time behavior by default so entry timestamps stay realistic; the dispatch-budget
    // test re-stubs elapsedRealtime to simulate time already spent inside a dispatch.
    private val mockClock: Clock = mockk(relaxed = true) {
        every { currentTimeSeconds() } answers { System.currentTimeMillis() / 1000 }
        every { currentTimeMillis() } answers { System.currentTimeMillis() }
        every { elapsedRealtime() } answers { android.os.SystemClock.elapsedRealtime() }
    }

    // Real disk-backed store (Robolectric filesDir). The mocked scheduler never
    // claims, so an appended entry stays in the store and we can assert on it.
    private val pendingStore get() = SDKComponent.android().pendingGeofenceDeliveryStore

    private lateinit var receiver: GeofenceBroadcastReceiver

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
                diGraph {
                    sdk {
                        overrideDependency<EventBus>(mockEventBus)
                        overrideDependency<Clock>(mockClock)
                        overrideDependency<Logger>(capturingLogger)
                    }
                    android {
                        overrideDependency<GeofenceEventScheduler>(mockScheduler)
                        overrideDependency<GeofenceServices>(mockServices)
                        overrideDependency<GeofenceCooldownFilter>(mockCooldownFilter)
                        overrideDependency<GeofenceRegionStore>(mockStore)
                        overrideDependency<GeofenceRegistrar>(mockManager)
                        overrideDependency<SecureUserStore>(mockSecureUserStore)
                    }
                }
            }
        )
        // The tail is gated, and these tests assert on `ev=` — the machine key a parser reads,
        // not the prose, which is what makes deleting a log line fail rather than just reword it.
        GeofenceDiagnostics.setEnabledForTesting(true)
        // Default: cooldown allows emission. Tests override this to test suppression.
        every { mockCooldownFilter.tryAcquire(any(), any(), any()) } returns null
        // Default: an identified user is the common case; the snapshot lands on the entry.
        // Tests that need an anonymous-at-queue-time scenario override this to null.
        every { mockSecureUserStore.getUserId() } returns "user-42"
        // Default: every id the tests reference is registered, so the orphan filter is a no-op.
        every { mockStore.getRegisteredIds() } returns setOf(
            GeofenceConstants.MOVEMENT_TRIGGER_ID,
            "biz-1",
            "biz-2",
            "biz-geofence",
            "biz-geofence-1",
            "biz-geofence-2"
        )
        // Default: the device counts as inside every fence, so the EXIT guard is a no-op.
        // Tests for the guard override.
        every { mockStore.claimExit(any()) } returns true
        // Default: containment has been recorded, i.e. not a freshly-upgraded install.
        every { mockStore.hasContainmentRecord() } returns true
        // Default: fences monitor both transitions, so the EXIT guard applies. Exit-only fences and
        // uncached ids are exempt from it and have their own tests.
        every { mockStore.getCachedRegion(any()) } returns
            GeofenceRegion("biz-geofence-2", 0.0, 0.0, 100f)
        pendingStore.removeAll()
        receiver = GeofenceBroadcastReceiver()
    }

    @Test
    fun handleGeofencingEvent_givenNullEvent_expectNothingScheduled() = runTest {
        receiver.handleGeofencingEvent(null)

        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
        pendingStore.loadAll() shouldBeEqualTo emptyList()
        expectRecorded("info", "broadcast_unparseable_intent")
    }

    @Test
    fun handleGeofencingEvent_givenHasError_expectNothingScheduled() = runTest {
        val event = mockk<GeofencingEvent>(relaxed = true) {
            every { hasError() } returns true
            every { errorCode } returns 1000
        }

        receiver.handleGeofencingEvent(event)

        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
        pendingStore.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun handleGeofencingEvent_givenNullTriggeringGeofences_expectNothingScheduled() = runTest {
        val event = mockk<GeofencingEvent>(relaxed = true) {
            every { hasError() } returns false
            every { geofenceTransition } returns Geofence.GEOFENCE_TRANSITION_ENTER
            every { triggeringGeofences } returns null
        }

        receiver.handleGeofencingEvent(event)

        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
        pendingStore.loadAll() shouldBeEqualTo emptyList()
        expectRecorded("info", "broadcast_no_triggering_geofences")
    }

    @Test
    fun handleGeofencingEvent_givenNullTriggeringLocation_expectEntryStillQueued() = runTest {
        // Business-geofence delivery doesn't depend on a triggering location, so
        // a null location must still queue the transition.
        val event = buildGeofencingEvent(
            transition = Geofence.GEOFENCE_TRANSITION_ENTER,
            geofenceIds = listOf("biz-1"),
            location = null
        )

        receiver.handleGeofencingEvent(event)

        val entry = pendingStore.loadAll().single()
        entry.geofenceId shouldBeEqualTo "biz-1"
    }

    @Test
    fun handleGeofencingEvent_givenValidEnterEvent_expectEntryQueued() = runTest {
        val event = buildGeofencingEvent(
            transition = Geofence.GEOFENCE_TRANSITION_ENTER,
            geofenceIds = listOf("biz-1"),
            location = realLocation(37.7749, -122.4194)
        )

        receiver.handleGeofencingEvent(event)

        val entry = pendingStore.loadAll().single()
        entry.geofenceId shouldBeEqualTo "biz-1"
        entry.transition shouldBeEqualTo Event.GeofenceTransition.ENTER
    }

    @Test
    fun handleGeofencingEvent_givenMovementTriggerExit_expectDispatchWaitsForRefreshJob() = runTest {
        // Movement usually fires with the app backgrounded: the moment dispatch returns
        // the goAsync window closes and the OS may kill the process mid-refresh, so
        // dispatch must hold the window open until the refresh job lands.
        val refreshJob = launch { delay(3_000) }
        every { mockServices.onMovementTriggerExit(any(), any()) } returns refreshJob

        receiver.handleGeofencingEvent(
            buildGeofencingEvent(
                transition = Geofence.GEOFENCE_TRANSITION_EXIT,
                geofenceIds = listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID),
                location = realLocation(1.0, 2.0)
            )
        )

        refreshJob.isCompleted shouldBeEqualTo true
    }

    @Test
    fun handleGeofencingEvent_givenHungRefreshJob_expectWaitBoundedAndJobNotCancelled() = runTest {
        // A hung GMS task must not blow the broadcast budget: the wait gives up after
        // its timeout, but only the wait — the refresh itself keeps running on the
        // services scope and self-completes if the process survives.
        val refreshJob = launch { delay(60_000) }
        every { mockServices.onMovementTriggerExit(any(), any()) } returns refreshJob

        receiver.handleGeofencingEvent(
            buildGeofencingEvent(
                transition = Geofence.GEOFENCE_TRANSITION_EXIT,
                geofenceIds = listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID),
                location = realLocation(1.0, 2.0)
            )
        )

        refreshJob.isActive shouldBeEqualTo true
        refreshJob.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun handleGeofencingEvent_givenDispatchBudgetAlreadySpent_expectNoWaitForRefreshJob() = runTest {
        // Persistence/GMS awaits earlier in a dispatch count against the same budget as the
        // join: once spent, dispatch must finish instead of stacking the full timeout on top.
        every { mockClock.elapsedRealtime() } returnsMany listOf(0L, 9_000L)
        val refreshJob = launch { delay(60_000) }
        every { mockServices.onMovementTriggerExit(any(), any()) } returns refreshJob

        receiver.handleGeofencingEvent(
            buildGeofencingEvent(
                transition = Geofence.GEOFENCE_TRANSITION_EXIT,
                geofenceIds = listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID),
                location = realLocation(1.0, 2.0)
            )
        )

        // No virtual time consumed: the join was skipped, not merely timed out.
        currentTime shouldBeEqualTo 0L
        refreshJob.isActive shouldBeEqualTo true
        refreshJob.cancel()
    }

    /** Pins the GMS-code → [GeofenceCrossingTransition] translation, the receiver's one remaining decision. */
    @Test
    fun handleGeofencingEvent_givenEachGmsTransitionCode_expectCorrectlyInterpreted() = runTest {
        val cases = listOf(
            Geofence.GEOFENCE_TRANSITION_ENTER to Event.GeofenceTransition.ENTER,
            Geofence.GEOFENCE_TRANSITION_EXIT to Event.GeofenceTransition.EXIT
        )
        for ((gmsCode, expected) in cases) {
            pendingStore.removeAll()
            receiver.handleGeofencingEvent(
                buildGeofencingEvent(
                    transition = gmsCode,
                    geofenceIds = listOf("biz-geofence-1"),
                    location = realLocation(1.0, 2.0)
                )
            )
            // Read the persisted row: MockK cannot capture into one slot across two verifies.
            pendingStore.loadAll().single().transition shouldBeEqualTo expected
        }

        pendingStore.removeAll()
        receiver.handleGeofencingEvent(
            buildGeofencingEvent(
                transition = Geofence.GEOFENCE_TRANSITION_DWELL,
                geofenceIds = listOf("biz-geofence-1"),
                location = realLocation(1.0, 2.0)
            )
        )
        pendingStore.loadAll() shouldBeEqualTo emptyList()
        expectRecorded("os.callback.dropped", "unsupported_transition_type")
        capturingLogger.messages.any {
            it.contains("ev=os.callback.dropped") && it.contains("gms=${Geofence.GEOFENCE_TRANSITION_DWELL}")
        } shouldBeEqualTo true
    }

    private fun buildGeofencingEvent(
        transition: Int,
        geofenceIds: List<String>,
        location: Location?
    ): GeofencingEvent {
        val geofences = geofenceIds.map { id ->
            mockk<Geofence>(relaxed = true) { every { requestId } returns id }
        }
        return mockk(relaxed = true) {
            every { hasError() } returns false
            every { geofenceTransition } returns transition
            every { triggeringGeofences } returns geofences
            every { triggeringLocation } returns location
        }
    }

    private fun realLocation(lat: Double, lng: Double): Location =
        Location("test-provider").apply {
            latitude = lat
            longitude = lng
            time = System.currentTimeMillis()
        }
}
