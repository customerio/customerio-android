package io.customer.geofence

import android.location.Location
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.polygon.PolygonBootSessionProvider
import io.customer.geofence.store.GeofenceRegionStoreImpl
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.geofence.worker.GeofenceEventScheduler
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Clock
import io.customer.sdk.data.store.PendingDeliveryStore
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContainSame
import org.amshove.kluent.shouldNotBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.amshove.kluent.shouldNotContain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The visit duration a native circle EXIT reports, through the real pipeline, store, emitter and
 * outbox. Receipt stamps advance on both clocks together unless a test steps the wall clock.
 */
@RunWith(RobolectricTestRunner::class)
class GeofenceVisitDurationRealStoreTest : RobolectricTest() {

    private val cooldownFilter: GeofenceCooldownFilter = mockk(relaxed = true)
    private val scheduler: GeofenceEventScheduler = mockk(relaxed = true)
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val logger: GeofenceLogger = mockk(relaxed = true)
    private lateinit var regionStore: GeofenceRegionStoreImpl
    private lateinit var outbox: PendingDeliveryStore<PendingGeofenceDelivery>

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
            }
        )
        regionStore = GeofenceRegionStoreImpl(
            context = applicationMock,
            jsonSerializer = GeofenceJsonSerializer(),
            logger = mockk(relaxed = true)
        ).also { it.clearAll() }
        regionStore.beginUserSession(USER_ID)
        regionStore.saveRegisteredIds(setOf(GEOFENCE_ID))
        regionStore.saveRoutableRegisteredIds(setOf(GEOFENCE_ID))
        every { secureUserStore.getUserId() } returns USER_ID
        every { cooldownFilter.suppressedForSeconds(any(), any(), any()) } returns null
        coEvery { scheduler.schedule(any()) } returns Unit
        outbox = PendingDeliveryStore(
            context = applicationMock,
            fileName = "cio_test_visit_duration_outbox.json",
            elementSerializer = PendingGeofenceDelivery.serializer(),
            logger = mockk(relaxed = true)
        ).also { it.removeAll() }
    }

    @Test
    fun exitOnlyFenceWithoutThreshold_givenObservedEntry_expectExitReportsTheVisit() = runTest {
        arm(exitOnlyRegion(), outsideProven = true)
        val pipeline = pipeline()

        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L))
        val visitId = regionStore.getDwellVisit(GEOFENCE_ID).shouldNotBeNull().visitId
        pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 76_000L))

        val exit = outbox.loadAll().single()
        exit.transition shouldBeEqualTo Event.GeofenceTransition.EXIT
        exit.toEventProperties().let { properties ->
            properties["visitId"] shouldBeEqualTo visitId
            properties["enteredAt"] shouldBeEqualTo wallSecondsAt(1_000L)
            properties["visitDurationSeconds"] shouldBeEqualTo 75L
            properties["detectionSource"] shouldBeEqualTo "native"
            properties["transitionId"] shouldNotBeEqualTo visitId
            properties.keys shouldNotContain "dwellDurationSeconds"
            properties.keys shouldNotContain "dwellThresholdSeconds"
        }
        regionStore.getDwellVisit(GEOFENCE_ID).shouldBeNull()
    }

    @Test
    fun exit_givenDistinctFixesWithinOneReportedSecond_expectNumericZeroDuration() = runTest {
        arm(exitOnlyRegion(), outsideProven = true)
        val pipeline = pipeline()

        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L))
        pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 1_400L))

        wallSecondsAt(1_000L) shouldBeEqualTo wallSecondsAt(1_400L)
        outbox.loadAll().single().toEventProperties()["visitDurationSeconds"] shouldBeEqualTo 0L
    }

    @Test
    fun exit_givenTheWallClockJumpedForwardMidVisit_expectVisitEndedWithoutDuration() = runTest {
        arm(exitOnlyRegion(), outsideProven = true)
        val pipeline = pipeline()
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L))
        val firstVisitId = regionStore.getDwellVisit(GEOFENCE_ID).shouldNotBeNull().visitId

        // 75 s on the boot clock, but network time moved the wall clock an hour ahead meanwhile.
        pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 76_000L, wallSeconds = wallSecondsAt(76_000L) + HOUR))

        val exit = outbox.loadAll().single()
        exit.visitDurationSeconds.shouldBeNull()
        exit.enteredAt.shouldBeNull()
        exit.visitId.shouldBeNull()
        regionStore.getDwellVisit(GEOFENCE_ID).shouldBeNull()
        regionStore.getEnteredIds().shouldBeEmpty()

        assertNextVisitIsDistinctAndMeasured(pipeline, firstVisitId, wallOffset = HOUR)
    }

    @Test
    fun exit_givenTheWallClockSteppedBackMidVisit_expectVisitEndedWithoutDuration() = runTest {
        arm(exitOnlyRegion(), outsideProven = true)
        val pipeline = pipeline()
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L, wallSeconds = wallSecondsAt(1_000L) + 2 * HOUR))
        val firstVisitId = regionStore.getDwellVisit(GEOFENCE_ID).shouldNotBeNull().visitId

        pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 76_000L, wallSeconds = wallSecondsAt(76_000L) + HOUR))

        outbox.loadAll().single().visitDurationSeconds.shouldBeNull()
        // The physical visit still ended, so the next entry is a new stay.
        regionStore.getDwellVisit(GEOFENCE_ID).shouldBeNull()
        regionStore.getEnteredIds().shouldBeEmpty()

        assertNextVisitIsDistinctAndMeasured(pipeline, firstVisitId, wallOffset = HOUR)
    }

    @Test
    fun exit_givenVisitStoredBeforeTimingProvenance_expectNoDuration() = runTest {
        arm(exitOnlyRegion(), outsideProven = true)
        val pipeline = pipeline()
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L))
        val visit = regionStore.getDwellVisit(GEOFENCE_ID).shouldNotBeNull()
        // An upgrade: the same visit as an older version wrote it, without boot or elapsed stamps.
        regionStore.saveDwellVisit(visit.copy(bootSessionId = null, enteredAtElapsedMs = null)).shouldBeTrue()

        pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 76_000L))

        outbox.loadAll().single().visitDurationSeconds.shouldBeNull()
        regionStore.getDwellVisit(GEOFENCE_ID).shouldBeNull()
    }

    @Test
    fun exit_givenVisitFromAnEarlierBoot_expectNoDuration() = runTest {
        arm(exitOnlyRegion(), outsideProven = true)
        pipeline().handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L))
        regionStore.getDwellVisit(GEOFENCE_ID).shouldNotBeNull()

        // After a reboot the fence is registered again in the new boot, whose uptime has passed the old one's.
        regionStore.recordRegistrationIncarnations(listOf(exitOnlyRegion()), registeredAtElapsedMs = 500L, bootSessionId = "boot-2")
        pipeline(bootSessionId = "boot-2").handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 76_000L))

        outbox.loadAll().single().visitDurationSeconds.shouldBeNull()
        regionStore.getDwellVisit(GEOFENCE_ID).shouldBeNull()
    }

    @Test
    fun exit_givenTheFenceWasReRegisteredMidVisit_expectNoDuration() = runTest {
        arm(exitOnlyRegion(), outsideProven = true)
        val pipeline = pipeline()
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L))

        regionStore.recordRegistrationIncarnations(listOf(exitOnlyRegion()), registeredAtElapsedMs = 30_000L, bootSessionId = BOOT)
        pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 76_000L))

        outbox.loadAll().single().visitDurationSeconds.shouldBeNull()
        regionStore.getDwellVisit(GEOFENCE_ID).shouldBeNull()
    }

    @Test
    fun exit_givenMonitoringContinuityWasLostMidVisit_expectNoDuration() = runTest {
        arm(exitOnlyRegion(), outsideProven = true)
        val pipeline = pipeline()
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L))

        // A permission or monitoring loss, then the registration that restored monitoring.
        regionStore.invalidateDwellContinuity()
        regionStore.recordRegistrationIncarnations(listOf(exitOnlyRegion()), registeredAtElapsedMs = 30_000L, bootSessionId = BOOT)
        pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 76_000L))

        outbox.loadAll().single().visitDurationSeconds.shouldBeNull()
    }

    @Test
    fun exit_givenTheUserChangedMidVisit_expectNoDurationForTheNewUser() = runTest {
        arm(exitOnlyRegion(), outsideProven = true)
        pipeline().handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L))

        regionStore.beginUserSession(OTHER_USER_ID)
        regionStore.saveRoutableRegisteredIds(setOf(GEOFENCE_ID))
        every { secureUserStore.getUserId() } returns OTHER_USER_ID
        pipeline().handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 76_000L))

        val exit = outbox.loadAll().single()
        exit.userId shouldBeEqualTo OTHER_USER_ID
        exit.visitDurationSeconds.shouldBeNull()
        exit.visitId.shouldBeNull()
    }

    @Test
    fun exit_givenNoVisitWasObserved_expectNoDuration() = runTest {
        arm(exitOnlyRegion(), outsideProven = true)

        // The ENTER was lost (or predates install), so this EXIT has nothing to pair with.
        pipeline().handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 76_000L))

        val exit = outbox.loadAll().single()
        exit.visitDurationSeconds.shouldBeNull()
        exit.visitId.shouldBeNull()
    }

    @Test
    fun exit_givenVisitRecoveredFromANativeDwell_expectNoDuration() = runTest {
        arm(exitOnlyRegion().copy(dwellThresholdSeconds = 60), outsideProven = true)
        val pipeline = pipeline()

        pipeline.handle(crossing(GeofenceCrossingTransition.DWELL, fixMs = 61_000L))
        regionStore.getDwellVisit(GEOFENCE_ID).shouldNotBeNull().entryWasObserved shouldBeEqualTo false
        pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 90_000L))

        val exit = outbox.loadAll().single { it.transition == Event.GeofenceTransition.EXIT }
        exit.visitDurationSeconds.shouldBeNull()
    }

    @Test
    fun exit_givenALateExitDecidedBetweenTheEntryAndANewerInsideFix_expectNoDuration() = runTest {
        arm(enterExitRegion(), outsideProven = true)
        val pipeline = pipeline()
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L))

        // GMS placed the device inside again at 5 000; the EXIT it decided at 2 000 arrives late.
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 5_000L))
        pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 2_000L))
        pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 90_000L))

        // The stay that began at 1 000 ended before 2 000, so 89 s would describe two stays.
        outbox.loadAll().map { it.transition } shouldBeEqualTo
            listOf(Event.GeofenceTransition.ENTER, Event.GeofenceTransition.EXIT)
        outbox.loadAll().last().visitDurationSeconds.shouldBeNull()
    }

    @Test
    fun exit_givenAnOlderExitDelayedPastANewerObservedEntry_expectTheNewerVisitStillMeasured() = runTest {
        arm(enterExitRegion(), outsideProven = true)
        val pipeline = pipeline()
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L))
        pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 5_000L))
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 9_000L))
        val secondVisit = regionStore.getDwellVisit(GEOFENCE_ID).shouldNotBeNull()
        secondVisit.entryWasObserved shouldBeEqualTo true

        // Decided at 3 000, before the newer entry's fix, so it says nothing about the current stay.
        pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 3_000L))
        regionStore.getDwellVisit(GEOFENCE_ID) shouldBeEqualTo secondVisit
        pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 12_000L))

        val exits = outbox.loadAll().filter { it.transition == Event.GeofenceTransition.EXIT }
        exits.map { it.visitDurationSeconds } shouldBeEqualTo listOf(4L, 3L)
        exits.last().visitId shouldBeEqualTo secondVisit.visitId
        exits.first().visitId shouldNotBeEqualTo secondVisit.visitId
    }

    @Test
    fun exit_givenAReEntryHandledBetweenTheExitsVisitCheckAndItsCommit_expectNoStaleDurationAndTheNewVisitKept() = runTest {
        arm(enterExitRegion(), outsideProven = true)
        val coordinator = spyk(coordinator())
        val pipeline = pipeline(coordinator = coordinator)
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L))
        val firstVisitId = regionStore.getDwellVisit(GEOFENCE_ID).shouldNotBeNull().visitId
        var reEntryRan = false
        // The EXIT at 76 000 has ended its visit; an ENTER at 80 000 completes before the EXIT commits.
        coEvery { coordinator.onNativeExit(any(), any(), any(), any(), any()) } coAnswers {
            val exit = callOriginal()
            if (!reEntryRan) {
                reEntryRan = true
                pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 80_000L))
            }
            exit
        }

        pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 76_000L))

        reEntryRan.shouldBeTrue()
        // The older EXIT is dropped behind the newer ENTER, so its duration is reported nowhere.
        outbox.loadAll().map { it.transition } shouldBeEqualTo listOf(Event.GeofenceTransition.ENTER)
        regionStore.getEnteredIds() shouldContainSame setOf(GEOFENCE_ID)
        val newerVisit = regionStore.getDwellVisit(GEOFENCE_ID).shouldNotBeNull()
        newerVisit.visitId shouldNotBeEqualTo firstVisitId
        // Its entry could not be told apart from the old stay, so it stays unknown.
        newerVisit.entryWasObserved shouldBeEqualTo false

        pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 140_000L))

        val exit = outbox.loadAll().last()
        exit.transition shouldBeEqualTo Event.GeofenceTransition.EXIT
        exit.visitDurationSeconds.shouldBeNull()
        regionStore.getDwellVisit(GEOFENCE_ID).shouldBeNull()
    }

    @Test
    fun queuedExit_givenCachesIdentityAndContinuityClearedAfterwards_expectItsVisitFactsUnchanged() = runTest {
        arm(exitOnlyRegion().copy(geosetIds = listOf("geoset-a", "geoset-b")), outsideProven = true)
        val pipeline = pipeline()
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L))
        val visitId = regionStore.getDwellVisit(GEOFENCE_ID).shouldNotBeNull().visitId
        pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 76_000L))
        val queued = outbox.loadAll()

        regionStore.saveCachedRegions(emptyList())
        regionStore.invalidateDwellContinuity()
        regionStore.beginUserSession(OTHER_USER_ID)
        every { secureUserStore.getUserId() } returns OTHER_USER_ID
        processor().recoverPendingTransitions().shouldBeTrue()

        outbox.loadAll() shouldBeEqualTo queued
        queued.map { it.geosetId } shouldBeEqualTo listOf("geoset-a", "geoset-b")
        queued.map { it.transitionId }.toSet().single() shouldNotBeEqualTo visitId
        queued.map { listOf(it.userId, it.visitId, it.enteredAt, it.visitDurationSeconds) }.toSet() shouldBeEqualTo
            setOf(listOf(USER_ID, visitId, wallSecondsAt(1_000L), 75L))
    }

    private suspend fun assertNextVisitIsDistinctAndMeasured(
        pipeline: GeofenceCrossingPipeline,
        firstVisitId: String,
        wallOffset: Long
    ) {
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 80_000L, wallSeconds = wallSecondsAt(80_000L) + wallOffset))
        val next = regionStore.getDwellVisit(GEOFENCE_ID).shouldNotBeNull()
        next.visitId shouldNotBeEqualTo firstVisitId
        next.entryWasObserved shouldBeEqualTo true
        pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 140_000L, wallSeconds = wallSecondsAt(140_000L) + wallOffset))

        val exit = outbox.loadAll().last()
        exit.visitId shouldBeEqualTo next.visitId
        exit.visitDurationSeconds shouldBeEqualTo 60L
    }

    private fun arm(region: GeofenceRegion, outsideProven: Boolean) {
        regionStore.saveCachedRegions(listOf(region))
        regionStore.recordRegistrationIncarnations(
            listOf(region),
            registeredAtElapsedMs = 500L,
            bootSessionId = BOOT,
            outsideIds = if (outsideProven) setOf(region.id) else emptySet()
        )
    }

    private fun coordinator(bootSessionId: String = BOOT) = GeofenceDwellCoordinator(
        regionStore,
        processor(),
        mockk<Clock>(relaxed = true),
        PolygonBootSessionProvider { bootSessionId }
    )

    private fun pipeline(
        bootSessionId: String = BOOT,
        coordinator: GeofenceDwellCoordinator = coordinator(bootSessionId)
    ) = GeofenceCrossingPipeline(
        regionStore = regionStore,
        services = mockk(relaxed = true),
        registrar = mockk(relaxed = true),
        transitionProcessor = processor(),
        dwellCoordinator = coordinator,
        polygonController = mockk(relaxed = true),
        logger = logger
    )

    private fun processor() = GeofenceBusinessTransitionProcessor(
        store = regionStore,
        secureUserStore = secureUserStore,
        transitionEmitter = GeofenceTransitionEmitter(cooldownFilter, outbox, scheduler, regionStore, logger),
        logger = logger
    )

    /** Receipt wall time for a fix, on a wall clock that agrees with the boot clock. */
    private fun wallSecondsAt(fixMs: Long) = 1_000L + (fixMs + 100L) / 1_000L

    private fun crossing(
        transition: GeofenceCrossingTransition,
        fixMs: Long,
        wallSeconds: Long = wallSecondsAt(fixMs)
    ) = GeofenceCrossing(
        geofenceIds = listOf(GEOFENCE_ID),
        transition = transition,
        transitionName = transition.name,
        rawTransitionCode = 0,
        latitude = 0.0,
        longitude = 0.0,
        triggeringLocation = Location("gps").apply {
            latitude = 0.0
            longitude = 0.0
            accuracy = 10f
            elapsedRealtimeNanos = fixMs * 1_000_000L
        },
        receivedAtSeconds = wallSeconds,
        receivedAtElapsedMs = fixMs + 100L
    )

    private fun exitOnlyRegion() = GeofenceRegion(
        id = GEOFENCE_ID,
        latitude = 0.0,
        longitude = 0.0,
        radius = 100f,
        transitionTypes = listOf(GeofenceTransitionType.EXIT),
        dwellThresholdSeconds = 0
    )

    private fun enterExitRegion() = exitOnlyRegion().copy(
        transitionTypes = listOf(GeofenceTransitionType.ENTER, GeofenceTransitionType.EXIT)
    )

    private companion object {
        const val USER_ID = "user-1"
        const val OTHER_USER_ID = "user-2"
        const val GEOFENCE_ID = "biz-1"
        const val BOOT = "boot"
        const val HOUR = 3_600L
    }
}
