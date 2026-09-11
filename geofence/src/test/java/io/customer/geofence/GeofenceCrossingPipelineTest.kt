package io.customer.geofence

import android.location.Location
import com.google.android.gms.location.Geofence
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.di.geofenceCrossingPipeline
import io.customer.geofence.di.pendingGeofenceDeliveryStore
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.geofence.worker.GeofenceEventScheduler
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.core.util.Clock
import io.customer.sdk.core.util.Logger
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeBlank
import org.amshove.kluent.shouldNotBeNull
import org.amshove.kluent.shouldNotContain
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceCrossingPipelineTest : RobolectricTest() {

    // 'mock' prefix to avoid shadowing SDKComponent.eventBus / AndroidSDKComponent
    // extension properties inside `sdk { ... }` / `android { ... }` override lambdas.
    private val mockEventBus: EventBus = mockk(relaxed = true)
    private val mockScheduler: GeofenceEventScheduler = mockk(relaxed = true)
    private val mockServices: GeofenceServices = mockk(relaxed = true)
    private val mockCooldownFilter: GeofenceCooldownFilter = mockk(relaxed = true)
    private val mockStore: GeofenceRegionStore = mockk(relaxed = true)
    private val mockManager: GeofenceRegistrar = mockk(relaxed = true)
    private val mockSecureUserStore: SecureUserStore = mockk(relaxed = true)

    /** Captures the emitted tail: the geofence logger is a computed singleton and cannot be mocked. */
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
        // null restores the manifest value; false would pin the gate off for later test classes.
        GeofenceDiagnostics.setEnabledForTesting(null)
    }

    private fun expectRecorded(ev: String, why: String) {
        val match = capturingLogger.messages.firstOrNull { it.contains("ev=$ev") && it.contains("why=$why") }
        if (match == null) {
            throw AssertionError(
                "expected a record with ev=$ev why=$why; captured:\n" +
                    capturingLogger.messages.joinToString("\n  ", prefix = "  ")
            )
        }
    }

    // Real time by default; the dispatch-budget test re-stubs elapsedRealtime.
    private val mockClock: Clock = mockk(relaxed = true) {
        every { currentTimeSeconds() } answers { System.currentTimeMillis() / 1000 }
        every { currentTimeMillis() } answers { System.currentTimeMillis() }
        every { elapsedRealtime() } answers { android.os.SystemClock.elapsedRealtime() }
    }

    // Real store: the mocked scheduler never claims, so appended entries stay assertable.
    private val pendingStore get() = SDKComponent.android().pendingGeofenceDeliveryStore

    /** The real pipeline, resolved from the overridden graph. */
    private val pipeline get() = SDKComponent.android().geofenceCrossingPipeline

    /** Feeds one crossing; GMS-code translation is the receiver's job and is tested there. */
    private suspend fun dispatchCrossing(
        transition: GeofenceCrossingTransition,
        geofenceIds: List<String>,
        latitude: Double? = null,
        longitude: Double? = null,
        rawTransitionCode: Int = Geofence.GEOFENCE_TRANSITION_ENTER
    ): Job? = pipeline.handle(
        GeofenceCrossing(
            geofenceIds = geofenceIds,
            transition = transition,
            transitionName = transition.name,
            rawTransitionCode = rawTransitionCode,
            latitude = latitude,
            longitude = longitude
        )
    )

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
        GeofenceDiagnostics.setEnabledForTesting(true)
        // Default: cooldown allows emission. Tests override this to test suppression.
        every { mockCooldownFilter.tryAcquire(any(), any(), any()) } returns null
        // Default: an identified user.
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
        every { mockStore.claimExit(any()) } returns true
        // Default: containment has been recorded, i.e. not a freshly-upgraded install.
        every { mockStore.hasContainmentRecord() } returns true
        // Default: fences monitor both transitions, so the EXIT guard applies.
        every { mockStore.getCachedRegion(any()) } returns
            GeofenceRegion("biz-geofence-2", 0.0, 0.0, 100f)
        pendingStore.removeAll()
    }

    @Test
    fun handle_givenEnterTransition_expectEntryAppendedAndScheduledButNotPublished() = runTest {
        val entrySlot = slot<PendingGeofenceDelivery>()

        dispatchCrossing(
            transition = GeofenceCrossingTransition.ENTER,
            geofenceIds = listOf("biz-geofence-1"),
            latitude = 37.7749,
            longitude = -122.4194
        )

        coVerify(exactly = 1) { mockScheduler.schedule(capture(entrySlot)) }
        val scheduled = entrySlot.captured
        scheduled.geofenceId shouldBeEqualTo "biz-geofence-1"
        scheduled.transition shouldBeEqualTo Event.GeofenceTransition.ENTER
        scheduled.toEventProperties()["transition"] shouldBeEqualTo "enter"
        scheduled.timestamp.shouldNotBeNull()
        scheduled.transitionId.shouldNotBeBlank()
        scheduled.toEventProperties()["transitionId"] shouldBeEqualTo scheduled.transitionId

        pendingStore.loadAll() shouldBeEqualTo listOf(scheduled)
        verify(exactly = 0) { mockEventBus.publish(any<Event.GeofenceTransitionEvent>()) }
    }

    @Test
    fun handle_givenIdentifiedUser_expectUserIdSnapshottedOnEntry() = runTest {
        every { mockSecureUserStore.getUserId() } returns "user-A"
        val entrySlot = slot<PendingGeofenceDelivery>()

        dispatchCrossing(
            transition = GeofenceCrossingTransition.ENTER,
            geofenceIds = listOf("biz-geofence-1"),
            latitude = 1.0,
            longitude = 2.0
        )

        coVerify(exactly = 1) { mockScheduler.schedule(capture(entrySlot)) }
        entrySlot.captured.userId shouldBeEqualTo "user-A"
    }

    @Test
    fun handle_givenAnonymousSession_expectDroppedNothingPersistedNorScheduled() = runTest {
        every { mockSecureUserStore.getUserId() } returns null

        dispatchCrossing(
            transition = GeofenceCrossingTransition.ENTER,
            geofenceIds = listOf("biz-geofence-1"),
            latitude = 1.0,
            longitude = 2.0
        )

        pendingStore.loadAll().shouldBeEmpty()
        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
    }

    @Test
    fun handle_givenEmptyUserId_expectTreatedAsAnonymousAndDropped() = runTest {
        every { mockSecureUserStore.getUserId() } returns ""

        dispatchCrossing(
            transition = GeofenceCrossingTransition.ENTER,
            geofenceIds = listOf("biz-geofence-1"),
            latitude = 1.0,
            longitude = 2.0
        )

        pendingStore.loadAll().shouldBeEmpty()
        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
    }

    @Test
    fun handle_givenExitTransition_expectEntryScheduled() = runTest {
        val entrySlot = slot<PendingGeofenceDelivery>()

        dispatchCrossing(
            transition = GeofenceCrossingTransition.EXIT,
            geofenceIds = listOf("biz-geofence-2"),
            latitude = 51.5074,
            longitude = -0.1278
        )

        coVerify(exactly = 1) { mockScheduler.schedule(capture(entrySlot)) }
        entrySlot.captured.transition shouldBeEqualTo Event.GeofenceTransition.EXIT
        entrySlot.captured.toEventProperties()["transition"] shouldBeEqualTo "exit"
    }

    @Test
    fun handle_givenMovementTriggerExit_expectServicesNotifiedAndNothingScheduled() = runTest {
        dispatchCrossing(
            transition = GeofenceCrossingTransition.EXIT,
            geofenceIds = listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID),
            latitude = 37.7749,
            longitude = -122.4194
        )

        verify { mockServices.onMovementTriggerExit(37.7749, -122.4194) }
        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
        pendingStore.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun handle_givenMovementTriggerNonExit_expectServicesNotNotified() = runTest {
        dispatchCrossing(
            transition = GeofenceCrossingTransition.ENTER,
            geofenceIds = listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID),
            latitude = 0.0,
            longitude = 0.0
        )

        verify(exactly = 0) { mockServices.onMovementTriggerExit(any(), any()) }
        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
        pendingStore.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun handle_givenUnknownTransitionType_expectNothingScheduled() = runTest {
        dispatchCrossing(
            transition = GeofenceCrossingTransition.UNSUPPORTED,
            rawTransitionCode = Geofence.GEOFENCE_TRANSITION_DWELL,
            geofenceIds = listOf("biz-geofence"),
            latitude = 0.0,
            longitude = 0.0
        )

        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
        pendingStore.loadAll() shouldBeEqualTo emptyList()
        expectRecorded("os.callback.dropped", "unsupported_transition_type")
        // Trailing space: "biz-geofence" is a prefix of "biz-geofence-2".
        capturingLogger.messages.any { it.contains("id=biz-geofence ") } shouldBeEqualTo true
    }

    @Test
    fun handle_givenMissingLocation_expectEntryStillScheduled() = runTest {
        val entrySlot = slot<PendingGeofenceDelivery>()

        dispatchCrossing(
            transition = GeofenceCrossingTransition.ENTER,
            geofenceIds = listOf("biz-geofence"),
            latitude = null,
            longitude = null
        )

        coVerify(exactly = 1) { mockScheduler.schedule(capture(entrySlot)) }
        entrySlot.captured.geofenceId shouldBeEqualTo "biz-geofence"
    }

    @Test
    fun handle_givenCachedRegionName_expectNameOnEntry() = runTest {
        every { mockStore.getCachedRegion("biz-geofence") } returns
            GeofenceRegion("biz-geofence", 0.0, 0.0, 100f, name = "Coffee Shop")
        val entrySlot = slot<PendingGeofenceDelivery>()

        dispatchCrossing(
            transition = GeofenceCrossingTransition.ENTER,
            geofenceIds = listOf("biz-geofence"),
            latitude = 0.0,
            longitude = 0.0
        )

        coVerify(exactly = 1) { mockScheduler.schedule(capture(entrySlot)) }
        entrySlot.captured.geofenceName shouldBeEqualTo "Coffee Shop"
    }

    @Test
    fun handle_givenCachedRegionMetadata_expectMetadataSnapshotOnEntry() = runTest {
        every { mockStore.getCachedRegion("biz-geofence") } returns
            GeofenceRegion("biz-geofence", 0.0, 0.0, 100f, metadata = mapOf("category" to JsonPrimitive("office")))
        val entrySlot = slot<PendingGeofenceDelivery>()

        dispatchCrossing(
            transition = GeofenceCrossingTransition.ENTER,
            geofenceIds = listOf("biz-geofence"),
            latitude = 0.0,
            longitude = 0.0
        )

        coVerify(exactly = 1) { mockScheduler.schedule(capture(entrySlot)) }
        entrySlot.captured.metadata shouldBeEqualTo mapOf("category" to JsonPrimitive("office"))
    }

    @Test
    fun handle_givenRegionWithMultipleGeosets_expectOneEventPerGeoset() = runTest {
        every { mockStore.getCachedRegion("biz-geofence") } returns
            GeofenceRegion("biz-geofence", 0.0, 0.0, 100f, name = "Mall", geosetIds = listOf("7", "8", "9"))
        val scheduled = mutableListOf<PendingGeofenceDelivery>()
        coEvery { mockScheduler.schedule(capture(scheduled)) } returns Unit

        dispatchCrossing(
            transition = GeofenceCrossingTransition.ENTER,
            geofenceIds = listOf("biz-geofence"),
            latitude = 0.0,
            longitude = 0.0
        )

        scheduled.map { it.geosetId } shouldBeEqualTo listOf("7", "8", "9")
        scheduled.map { it.toEventProperties()["geosetId"] } shouldBeEqualTo listOf("7", "8", "9")
        scheduled.map { it.transitionId }.toSet().size shouldBeEqualTo 1
        scheduled.map { it.key }.toSet().size shouldBeEqualTo 3
        verify(exactly = 1) { mockCooldownFilter.tryAcquire("user-42", "biz-geofence", Event.GeofenceTransition.ENTER) }
        pendingStore.loadAll().size shouldBeEqualTo 3
    }

    @Test
    fun handle_givenPersistFails_expectNoScheduleAndCooldownReleased() = runTest {
        every { mockStore.getCachedRegion("biz-geofence") } returns
            GeofenceRegion("biz-geofence", 0.0, 0.0, 100f)
        // Turning the backing file into a directory makes the store's write fail.
        val storeFile = File(applicationMock.applicationContext.filesDir, PendingGeofenceDelivery.FILE_NAME)
        storeFile.delete()
        storeFile.mkdirs()

        dispatchCrossing(
            transition = GeofenceCrossingTransition.ENTER,
            geofenceIds = listOf("biz-geofence"),
            latitude = 0.0,
            longitude = 0.0
        )

        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
        verify(exactly = 1) { mockCooldownFilter.release("user-42", "biz-geofence", Event.GeofenceTransition.ENTER) }
    }

    @Test
    fun handle_givenRegionWithDuplicateGeosets_expectDedupedInOrder() = runTest {
        every { mockStore.getCachedRegion("biz-geofence") } returns
            GeofenceRegion("biz-geofence", 0.0, 0.0, 100f, geosetIds = listOf("7", "8", "7"))
        val scheduled = mutableListOf<PendingGeofenceDelivery>()
        coEvery { mockScheduler.schedule(capture(scheduled)) } returns Unit

        dispatchCrossing(
            transition = GeofenceCrossingTransition.ENTER,
            geofenceIds = listOf("biz-geofence"),
            latitude = 0.0,
            longitude = 0.0
        )

        scheduled.map { it.geosetId } shouldBeEqualTo listOf("7", "8")
        pendingStore.loadAll().size shouldBeEqualTo 2
    }

    @Test
    fun handle_givenRegionWithSingleGeoset_expectSingleEventWithGeoset() = runTest {
        every { mockStore.getCachedRegion("biz-geofence") } returns
            GeofenceRegion("biz-geofence", 0.0, 0.0, 100f, geosetIds = listOf("5"))
        val entrySlot = slot<PendingGeofenceDelivery>()

        dispatchCrossing(
            transition = GeofenceCrossingTransition.ENTER,
            geofenceIds = listOf("biz-geofence"),
            latitude = 0.0,
            longitude = 0.0
        )

        coVerify(exactly = 1) { mockScheduler.schedule(capture(entrySlot)) }
        entrySlot.captured.geosetId shouldBeEqualTo "5"
    }

    @Test
    fun handle_givenRegionWithNoGeosets_expectSingleEventWithoutGeoset() = runTest {
        val entrySlot = slot<PendingGeofenceDelivery>()

        dispatchCrossing(
            transition = GeofenceCrossingTransition.ENTER,
            geofenceIds = listOf("biz-geofence"),
            latitude = 0.0,
            longitude = 0.0
        )

        coVerify(exactly = 1) { mockScheduler.schedule(capture(entrySlot)) }
        entrySlot.captured.geosetId.shouldBeNull()
        entrySlot.captured.toEventProperties().keys shouldNotContain "geosetId"
    }

    @Test
    fun handle_givenCooldownSuppresses_expectNothingScheduled() = runTest {
        every { mockCooldownFilter.tryAcquire("user-42", "biz-geofence", Event.GeofenceTransition.ENTER) } returns 120.0

        dispatchCrossing(
            transition = GeofenceCrossingTransition.ENTER,
            geofenceIds = listOf("biz-geofence"),
            latitude = 0.0,
            longitude = 0.0
        )

        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
        pendingStore.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun handle_givenCooldownAllows_expectTryAcquireBeforeSchedule() = runTest {
        dispatchCrossing(
            transition = GeofenceCrossingTransition.ENTER,
            geofenceIds = listOf("biz-geofence"),
            latitude = 0.0,
            longitude = 0.0
        )

        coVerifyOrder {
            mockCooldownFilter.tryAcquire("user-42", "biz-geofence", Event.GeofenceTransition.ENTER)
            mockScheduler.schedule(any())
        }
    }

    @Test
    fun handle_givenBatchWithMovementTriggerAndBusiness_expectOnlyBusinessScheduled() = runTest {
        val scheduled = mutableListOf<PendingGeofenceDelivery>()
        coEvery { mockScheduler.schedule(capture(scheduled)) } returns Unit

        dispatchCrossing(
            transition = GeofenceCrossingTransition.ENTER,
            geofenceIds = listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "biz-geofence"),
            latitude = 0.0,
            longitude = 0.0
        )

        scheduled.map { it.geofenceId } shouldBeEqualTo listOf("biz-geofence")
    }

    @Test
    fun handle_givenSchedulerThrows_expectEntryStillRecordedForFlush() = runTest {
        coEvery { mockScheduler.schedule(any()) } throws RuntimeException("WM internal")

        dispatchCrossing(
            transition = GeofenceCrossingTransition.ENTER,
            geofenceIds = listOf("biz-geofence-1"),
            latitude = 1.0,
            longitude = 2.0
        )

        pendingStore.loadAll().single().geofenceId shouldBeEqualTo "biz-geofence-1"
    }

    @Test
    fun handle_givenSchedulerThrowsOnFirstGeofence_expectSecondGeofenceStillProcessed() = runTest {
        coEvery { mockScheduler.schedule(match { it.geofenceId == "biz-1" }) } throws RuntimeException("WM internal")
        coEvery { mockScheduler.schedule(match { it.geofenceId == "biz-2" }) } returns Unit

        dispatchCrossing(
            transition = GeofenceCrossingTransition.ENTER,
            geofenceIds = listOf("biz-1", "biz-2"),
            latitude = 0.0,
            longitude = 0.0
        )

        pendingStore.loadAll().map { it.geofenceId } shouldBeEqualTo listOf("biz-1", "biz-2")
        coVerify { mockScheduler.schedule(match { it.geofenceId == "biz-2" }) }
    }

    @Test
    fun handle_givenExitForFenceNeverEntered_expectDroppedAndOsRegistrationKept() = runTest {
        every { mockStore.claimExit("biz-geofence-2") } returns false

        dispatchCrossing(
            transition = GeofenceCrossingTransition.EXIT,
            geofenceIds = listOf("biz-geofence-2"),
            latitude = 51.5074,
            longitude = -0.1278
        )

        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
        pendingStore.loadAll() shouldBeEqualTo emptyList()
        verify(exactly = 0) { mockCooldownFilter.tryAcquire(any(), any(), any()) }
        coVerify(exactly = 0) { mockManager.removeGeofencesByIds(any()) }
    }

    @Test
    fun handle_givenUnmatchedExitForExitOnlyFence_expectDeliveredNotDropped() = runTest {
        every { mockStore.claimExit("biz-geofence-2") } returns false
        every { mockStore.getCachedRegion("biz-geofence-2") } returns GeofenceRegion(
            id = "biz-geofence-2",
            latitude = 0.0,
            longitude = 0.0,
            radius = 100f,
            transitionTypes = listOf(GeofenceTransitionType.EXIT)
        )

        dispatchCrossing(
            transition = GeofenceCrossingTransition.EXIT,
            geofenceIds = listOf("biz-geofence-2"),
            latitude = 51.5074,
            longitude = -0.1278
        )

        coVerify(exactly = 1) { mockScheduler.schedule(any()) }
    }

    @Test
    fun handle_givenUnmatchedExitForUncachedFence_expectDeliveredNotDropped() = runTest {
        every { mockStore.claimExit("biz-geofence-2") } returns false
        every { mockStore.getCachedRegion("biz-geofence-2") } returns null

        dispatchCrossing(
            transition = GeofenceCrossingTransition.EXIT,
            geofenceIds = listOf("biz-geofence-2"),
            latitude = 51.5074,
            longitude = -0.1278
        )

        coVerify(exactly = 1) { mockScheduler.schedule(any()) }
    }

    @Test
    fun handle_givenUnmatchedExitOnUpgradedInstall_expectDeliveredNotDropped() = runTest {
        every { mockStore.claimExit("biz-geofence-2") } returns false
        every { mockStore.hasContainmentRecord() } returns false

        dispatchCrossing(
            transition = GeofenceCrossingTransition.EXIT,
            geofenceIds = listOf("biz-geofence-2"),
            latitude = 51.5074,
            longitude = -0.1278
        )

        coVerify(exactly = 1) { mockScheduler.schedule(any()) }
    }

    @Test
    fun handle_givenReportedEnterForFenceMonitoringExit_expectDropped() = runTest {
        every { mockStore.hasEmittedEnter("user-42", "biz-geofence-2") } returns true

        dispatchCrossing(
            transition = GeofenceCrossingTransition.ENTER,
            geofenceIds = listOf("biz-geofence-2"),
            latitude = 51.5074,
            longitude = -0.1278
        )

        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
    }

    @Test
    fun handle_givenReportedEnterForEnterOnlyFence_expectDelivered() = runTest {
        every { mockStore.hasEmittedEnter("user-42", "biz-geofence-2") } returns true
        every { mockStore.getCachedRegion("biz-geofence-2") } returns GeofenceRegion(
            id = "biz-geofence-2",
            latitude = 0.0,
            longitude = 0.0,
            radius = 100f,
            transitionTypes = listOf(GeofenceTransitionType.ENTER)
        )

        dispatchCrossing(
            transition = GeofenceCrossingTransition.ENTER,
            geofenceIds = listOf("biz-geofence-2"),
            latitude = 51.5074,
            longitude = -0.1278
        )

        coVerify(exactly = 1) { mockScheduler.schedule(any()) }
    }

    @Test
    fun handle_givenConcurrentBroadcastsForSameFence_expectBookkeepingSerialized() = runTest {
        // Without the lock an ENTER and an EXIT for one fence interleave read-decide-persist.
        val inFlight = AtomicInteger()
        val peakInFlight = AtomicInteger()
        coEvery { mockScheduler.schedule(any()) } coAnswers {
            val depth = inFlight.incrementAndGet()
            peakInFlight.updateAndGet { peak -> maxOf(peak, depth) }
            delay(100)
            inFlight.decrementAndGet()
        }

        val enter = launch {
            dispatchCrossing(
                transition = GeofenceCrossingTransition.ENTER,
                geofenceIds = listOf("biz-1"),
                latitude = 0.0,
                longitude = 0.0
            )
        }
        val exit = launch {
            dispatchCrossing(
                transition = GeofenceCrossingTransition.EXIT,
                geofenceIds = listOf("biz-1"),
                latitude = 0.0,
                longitude = 0.0
            )
        }
        enter.join()
        exit.join()

        peakInFlight.get() shouldBeEqualTo 1
    }

    @Test
    fun handle_givenEnterTransition_expectContainmentRecorded() = runTest {
        dispatchCrossing(
            transition = GeofenceCrossingTransition.ENTER,
            geofenceIds = listOf("biz-geofence-2"),
            latitude = 51.5074,
            longitude = -0.1278
        )

        verify { mockStore.recordEntered("biz-geofence-2") }
    }

    @Test
    fun handle_givenAnonymousEnter_expectContainmentStillRecorded() = runTest {
        every { mockSecureUserStore.getUserId() } returns null

        dispatchCrossing(
            transition = GeofenceCrossingTransition.ENTER,
            geofenceIds = listOf("biz-geofence-2"),
            latitude = 51.5074,
            longitude = -0.1278
        )

        verify { mockStore.recordEntered("biz-geofence-2") }
        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
    }

    @Test
    fun handle_givenMovementTriggerExit_expectGuardNotApplied() = runTest {
        dispatchCrossing(
            transition = GeofenceCrossingTransition.EXIT,
            geofenceIds = listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID),
            latitude = 0.0,
            longitude = 0.0
        )

        verify(exactly = 0) { mockStore.claimExit(any()) }
        verify { mockServices.onMovementTriggerExit(any(), any()) }
    }

    @Test
    fun handle_givenIdNotInStore_expectDroppedAndRemovedFromOs() = runTest {
        every { mockStore.getRegisteredIds() } returns setOf("biz-known")

        dispatchCrossing(
            transition = GeofenceCrossingTransition.ENTER,
            geofenceIds = listOf("biz-orphan"),
            latitude = 0.0,
            longitude = 0.0
        )

        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
        pendingStore.loadAll() shouldBeEqualTo emptyList()
        verify(exactly = 0) { mockCooldownFilter.tryAcquire(any(), any(), any()) }
        coVerify { mockManager.removeGeofencesByIds(listOf("biz-orphan")) }
    }

    @Test
    fun handle_givenMovementTriggerNotInStore_expectMovementHandlerNotCalledAndRemoved() = runTest {
        every { mockStore.getRegisteredIds() } returns setOf("biz-known")

        dispatchCrossing(
            transition = GeofenceCrossingTransition.EXIT,
            geofenceIds = listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID),
            latitude = 0.0,
            longitude = 0.0
        )

        verify(exactly = 0) { mockServices.onMovementTriggerExit(any(), any()) }
        coVerify { mockManager.removeGeofencesByIds(listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID)) }
    }

    @Test
    fun handle_givenMixedKnownAndUnknownIds_expectOnlyKnownProcessedAndUnknownsRemovedAsBatch() = runTest {
        every { mockStore.getRegisteredIds() } returns setOf("biz-known")
        val scheduled = mutableListOf<PendingGeofenceDelivery>()
        coEvery { mockScheduler.schedule(capture(scheduled)) } returns Unit

        dispatchCrossing(
            transition = GeofenceCrossingTransition.ENTER,
            geofenceIds = listOf("biz-orphan-1", "biz-known", "biz-orphan-2"),
            latitude = 0.0,
            longitude = 0.0
        )

        scheduled.map { it.geofenceId } shouldBeEqualTo listOf("biz-known")
        coVerify(exactly = 1) {
            mockManager.removeGeofencesByIds(listOf("biz-orphan-1", "biz-orphan-2"))
        }
    }

    @Test
    fun handle_givenAllIdsKnown_expectNoRemoveCall() = runTest {
        dispatchCrossing(
            transition = GeofenceCrossingTransition.ENTER,
            geofenceIds = listOf("biz-1"),
            latitude = 0.0,
            longitude = 0.0
        )

        coVerify(exactly = 0) { mockManager.removeGeofencesByIds(any()) }
    }

    private fun realLocation(lat: Double, lng: Double): Location =
        Location("test-provider").apply {
            latitude = lat
            longitude = lng
            time = System.currentTimeMillis()
        }
}
