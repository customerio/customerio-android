package io.customer.geofence.polygon

import android.location.Location
import android.os.SystemClock
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.GeofenceBusinessTransitionProcessor
import io.customer.geofence.GeofenceConstants
import io.customer.geofence.GeofenceCooldownFilter
import io.customer.geofence.GeofenceDwellCoordinator
import io.customer.geofence.GeofenceJsonSerializer
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.GeofenceTransitionEmitter
import io.customer.geofence.GeofenceTransitionType
import io.customer.geofence.PolygonFixRejection
import io.customer.geofence.store.GeofenceDwellVisit
import io.customer.geofence.store.GeofenceRegionStoreImpl
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Clock
import io.customer.sdk.data.store.PendingDeliveryStore
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldContainSame
import org.amshove.kluent.shouldNotBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PolygonLocationEngineTest : RobolectricTest() {
    private val emitter: GeofenceTransitionEmitter = mockk(relaxed = true)
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val logger: GeofenceLogger = mockk(relaxed = true)
    private val clock: Clock = mockk(relaxed = true)
    private val dwellCoordinator: GeofenceDwellCoordinator = mockk(relaxed = true)
    private lateinit var store: GeofenceRegionStoreImpl
    private lateinit var engine: PolygonLocationEngine

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
            }
        )
        store = GeofenceRegionStoreImpl(
            context = applicationMock,
            jsonSerializer = GeofenceJsonSerializer(),
            logger = mockk(relaxed = true)
        ).also { it.clearAll() }
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        store.saveCachedRegions(listOf(polygonRegion()))
        store.beginUserSession("user-1")
        store.saveRegisteredIds(setOf(POLYGON_ID))
        store.saveRoutableRegisteredIds(setOf(POLYGON_ID))
        store.activatePolygon(POLYGON_ID)
        store.recordPolygonCoarseInside(POLYGON_ID)
        every { secureUserStore.getUserId() } returns "user-1"
        every { clock.currentTimeSeconds() } returns 100L
        every { clock.currentTimeMillis() } returns 100_000L
        coEvery { emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        coEvery { emitter.recoverPendingTransitions() } returns true
        coEvery { dwellCoordinator.capturePolygonExit(any(), any(), any(), any()) } returns
            GeofenceDwellCoordinator.PolygonExit()
        engine = PolygonLocationEngine(
            store = store,
            transitionProcessor = GeofenceBusinessTransitionProcessor(
                store,
                secureUserStore,
                emitter,
                logger
            ),
            clock = clock,
            logger = logger,
            dwellCoordinator = dwellCoordinator
        )
    }

    // ---------- the catalog can change under an unchanged active set ----------

    @Test
    fun processResponsiveLocation_givenTheRingMovedUnderTheSameId_expectJudgedAgainstTheNewRing() = runTest {
        // A sync can replace a ring without changing the active set; a fence cache keyed on the
        // active ids alone would keep evaluating the old ring.
        engine.processResponsiveLocation(insideFix())
        store.getEnteredIds() shouldContainSame setOf(POLYGON_ID)

        // Ring moved ~1 km north, so the old fix is now well outside it.
        store.saveCachedRegions(
            listOf(
                polygonRegion().copy(
                    polygonVertices = listOf(
                        PolygonCoordinate(37.7845, -122.4200),
                        PolygonCoordinate(37.7845, -122.4188),
                        PolygonCoordinate(37.7855, -122.4188),
                        PolygonCoordinate(37.7855, -122.4200)
                    )
                )
            )
        )

        engine.processResponsiveLocation(insideFix(elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()))

        store.getEnteredIds().shouldBeEmpty()
    }

    // ---------- what a single decisive fix does ----------

    @Test
    fun processResponsiveLocation_givenOneDecisiveInsideFix_expectCommittedEnter() = runTest {
        engine.processResponsiveLocation(insideFix())

        store.getEnteredIds() shouldContainSame setOf(POLYGON_ID)
        coVerify(exactly = 1) {
            emitter.emitWithRetainedAttempt(
                POLYGON_ID,
                Event.GeofenceTransition.ENTER,
                "user-1",
                100L,
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun processResponsiveLocation_givenSameFixRepeated_expectExactlyOneEnter() = runTest {
        val fix = insideFix()

        repeat(3) { engine.processResponsiveLocation(fix) }

        store.getEnteredIds() shouldContainSame setOf(POLYGON_ID)
        coVerify(exactly = 1) {
            emitter.emitWithRetainedAttempt(
                any(),
                Event.GeofenceTransition.ENTER,
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun processResponsiveLocation_givenFreshDecisiveInsideFixForEnteredFence_expectDwellEvidence() = runTest {
        engine.processResponsiveLocation(insideFix())
        engine.processResponsiveLocation(
            insideFix(elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos())
        )

        coVerify(exactly = 1) {
            dwellCoordinator.onInsideEvidence(POLYGON_ID, 100L, any(), any())
        }
    }

    @Test
    fun processResponsiveLocation_givenFirstFixAlreadyInside_expectDiscoveredVisitNotObservedEntry() = runTest {
        // No record reads as OUTSIDE, so a device already inside when the polygon activated reports
        // ENTER without any crossing. Public ENTER is unchanged; the visit has no observed entry.
        val dwellEngine = engineWithRealDwell()

        dwellEngine.processResponsiveLocation(insideFix())

        store.getEnteredIds() shouldContainSame setOf(POLYGON_ID)
        store.getDwellVisit(POLYGON_ID)?.entryWasObserved shouldBeEqualTo false
    }

    @Test
    fun processResponsiveLocation_givenDecisiveOutsideThenInside_expectObservedEntry() = runTest {
        val dwellEngine = engineWithRealDwell()
        val now = SystemClock.elapsedRealtimeNanos()

        dwellEngine.processResponsiveLocation(outsideFix(elapsedRealtimeNanos = now - 3_000_000_000L))
        dwellEngine.processResponsiveLocation(insideFix(elapsedRealtimeNanos = now - 2_000_000_000L))

        store.getDwellVisit(POLYGON_ID)?.entryWasObserved shouldBeEqualTo true
        store.getDwellVisit(POLYGON_ID)?.enteredAtSeconds shouldBeEqualTo 100L
    }

    @Test
    fun processResponsiveLocation_givenOutsideProofOlderThanTheWindow_expectNoObservedEntry() = runTest {
        // A long stretch without fixes: the device arrived somewhere in it, so the inside fix's time
        // is not its entry. Public ENTER still fires; the visit just claims no observed start.
        val dwellEngine = engineWithRealDwell()

        dwellEngine.processResponsiveLocation(outsideFix(elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()))
        ShadowSystemClock.advanceBy(Duration.ofMillis(GeofenceConstants.MAX_OUTSIDE_PROOF_AGE_MS + 1))
        dwellEngine.processResponsiveLocation(insideFix(elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()))

        store.getEnteredIds() shouldContainSame setOf(POLYGON_ID)
        store.getDwellVisit(POLYGON_ID)?.entryWasObserved shouldBeEqualTo false
    }

    @Test
    fun processResponsiveLocation_givenOutsideProofAtTheWindowEdge_expectObservedEntry() = runTest {
        val dwellEngine = engineWithRealDwell()

        dwellEngine.processResponsiveLocation(outsideFix(elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()))
        ShadowSystemClock.advanceBy(Duration.ofMillis(GeofenceConstants.MAX_OUTSIDE_PROOF_AGE_MS))
        dwellEngine.processResponsiveLocation(insideFix(elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()))

        store.getEnteredIds() shouldContainSame setOf(POLYGON_ID)
        store.getDwellVisit(POLYGON_ID)?.entryWasObserved shouldBeEqualTo true
    }

    @Test
    fun processResponsiveLocation_givenOutsideProofResetBeforeInside_expectNoObservedEntry() = runTest {
        // Deactivation, a geometry change or re-activation ends the continuity the outside fix proved.
        val dwellEngine = engineWithRealDwell()
        val now = SystemClock.elapsedRealtimeNanos()

        dwellEngine.processResponsiveLocation(outsideFix(elapsedRealtimeNanos = now - 3_000_000_000L))
        dwellEngine.resetEvidence(POLYGON_ID)
        dwellEngine.processResponsiveLocation(insideFix(elapsedRealtimeNanos = now - 2_000_000_000L))

        store.getEnteredIds() shouldContainSame setOf(POLYGON_ID)
        store.getDwellVisit(POLYGON_ID)?.entryWasObserved shouldBeEqualTo false
    }

    @Test
    fun processResponsiveLocation_givenExitAfterTheWallClockSteppedBack_expectTheVisitEnded() = runTest {
        // The EXIT's wall stamp (10 s) precedes the entry's (100 s), but its fix is 1 s later.
        val dwellEngine = engineWithRealDwell()
        val now = SystemClock.elapsedRealtimeNanos()
        dwellEngine.processResponsiveLocation(insideFix(elapsedRealtimeNanos = now - 3_000_000_000L))
        store.getDwellVisit(POLYGON_ID).shouldNotBeNull()

        dwellEngine.processResponsiveLocation(
            fix(37.7750, -122.4175, elapsedRealtimeNanos = now - 2_000_000_000L, timestampMillis = 10_000L)
        )

        store.getEnteredIds().shouldBeEmpty()
        // Left behind, a process death before the next ENTER's visit write would let the next
        // inside fix resume it across the time spent outside.
        store.getDwellVisit(POLYGON_ID).shouldBeNull()
    }

    @Test
    fun processResponsiveLocation_givenLegacyVisitAndBackwardClockStep_expectExitEndsTheVisit() = runTest {
        val contexts = captureVisitContexts()
        val dwellEngine = engineWithRealDwell(exitOnlyPolygon())
        val now = SystemClock.elapsedRealtimeNanos()
        dwellEngine.processResponsiveLocation(insideFix(elapsedRealtimeNanos = now - 3_000_000_000L))
        val legacy = store.getDwellVisit(POLYGON_ID).shouldNotBeNull()
        store.saveDwellVisit(legacy.copy(bootSessionId = null, enteredAtElapsedMs = null)) shouldBeEqualTo true

        // The fresh outside fix ends the stay even though the legacy entry has no comparable boot stamp.
        dwellEngine.processResponsiveLocation(
            fix(37.7750, -122.4175, elapsedRealtimeNanos = now - 2_000_000_000L, timestampMillis = 10_000L)
        )

        store.getDwellVisit(POLYGON_ID).shouldBeNull()
        val legacyExit = contexts.filterIsInstance<GeofenceTransitionEmitter.VisitContext.Exit>().single()
        legacyExit.visitId shouldBeEqualTo legacy.visitId
        legacyExit.enteredAt.shouldBeNull()
        legacyExit.durationSeconds.shouldBeNull()

        dwellEngine.processResponsiveLocation(
            insideFix(elapsedRealtimeNanos = now - 1_000_000_000L, timestampMillis = 20_000L)
        )
        store.getDwellVisit(POLYGON_ID).shouldNotBeNull().visitId shouldNotBeEqualTo legacy.visitId
    }

    @Test
    fun processResponsiveLocation_givenEnteredFenceAndAPointInsideByLessThanItsAccuracy_expectNoDwellEvidence() = runTest {
        engine.processResponsiveLocation(insideFix())

        // 5 m inside the north edge with 40 m accuracy: the fix's uncertainty reaches outside.
        engine.processResponsiveLocation(marginalInsideFix(elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 1_000_000_000L))

        store.getEnteredIds() shouldContainSame setOf(POLYGON_ID)
        coVerify(exactly = 0) { dwellCoordinator.onInsideEvidence(any(), any(), any(), any()) }

        // About 53 m inside with 20 m accuracy is wholly inside the ring.
        engine.processResponsiveLocation(insideFix(elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(), accuracyMeters = 20f))

        coVerify(exactly = 1) { dwellCoordinator.onInsideEvidence(POLYGON_ID, any(), any(), any()) }
    }

    @Test
    fun processResponsiveLocation_givenMarginalInsideAfterTheThreshold_expectNoDwellUntilAClearInsideFix() = runTest {
        store.saveCachedRegions(listOf(polygonRegion().copy(dwellThresholdSeconds = 60)))
        val outbox = PendingDeliveryStore(
            context = applicationMock,
            fileName = "cio_test_marginal_inside_outbox.json",
            elementSerializer = PendingGeofenceDelivery.serializer(),
            logger = mockk(relaxed = true)
        ).also { it.removeAll() }
        val cooldownFilter: GeofenceCooldownFilter = mockk(relaxed = true) {
            every { suppressedForSeconds(any(), any(), any()) } returns null
        }
        val processor = GeofenceBusinessTransitionProcessor(
            store,
            secureUserStore,
            GeofenceTransitionEmitter(cooldownFilter, outbox, mockk(relaxed = true), store, logger),
            logger
        )
        val realEngine = PolygonLocationEngine(
            store = store,
            transitionProcessor = processor,
            clock = clock,
            logger = logger,
            dwellCoordinator = GeofenceDwellCoordinator(store, processor, clock) { "boot" }
        )
        fun dwells() = outbox.loadAll().filter { it.transition == Event.GeofenceTransition.DWELL }

        realEngine.processResponsiveLocation(insideFix(elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 1_000_000_000L))
        val visit = store.getDwellVisit(POLYGON_ID).shouldNotBeNull()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(70))

        realEngine.processResponsiveLocation(marginalInsideFix(elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 1_000_000_000L))

        // Past the threshold, but this fix cannot show the device stayed inside.
        dwells().shouldBeEmpty()
        store.getEnteredIds() shouldContainSame setOf(POLYGON_ID)
        store.getDwellVisit(POLYGON_ID) shouldBeEqualTo visit

        realEngine.processResponsiveLocation(insideFix(elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(), accuracyMeters = 20f))

        dwells().map { it.transitionId } shouldBeEqualTo listOf(visit.visitId)
    }

    private fun engineWithRealDwell(
        region: GeofenceRegion = polygonRegion().copy(dwellThresholdSeconds = 60),
        dwellStore: GeofenceRegionStoreImpl = store
    ): PolygonLocationEngine {
        store.saveCachedRegions(listOf(region))
        val processor = GeofenceBusinessTransitionProcessor(dwellStore, secureUserStore, emitter, logger)
        return PolygonLocationEngine(
            store = dwellStore,
            transitionProcessor = processor,
            clock = clock,
            logger = logger,
            dwellCoordinator = GeofenceDwellCoordinator(dwellStore, processor, clock) { "boot" }
        )
    }

    // ---------- the visit duration a polygon EXIT reports ----------

    @Test
    fun processResponsiveLocation_givenExitOnlyPolygonWithoutThreshold_expectExitReportsTheObservedVisit() = runTest {
        val contexts = captureVisitContexts()
        val dwellEngine = engineWithRealDwell(exitOnlyPolygon())

        val visitId = observeVisitThenExit(dwellEngine, exitTimestampMillis = 175_000L)

        val exit = contexts.filterIsInstance<GeofenceTransitionEmitter.VisitContext.Exit>().single()
        exit.visitId shouldBeEqualTo visitId
        exit.enteredAt shouldBeEqualTo 100L
        exit.durationSeconds shouldBeEqualTo 75L
        exit.detectionSource shouldBeEqualTo "location_evidence"
        store.getDwellVisit(POLYGON_ID).shouldBeNull()
    }

    @Test
    fun processResponsiveLocation_givenTheWallClockJumpedForwardMidVisit_expectExitWithoutDuration() = runTest {
        val contexts = captureVisitContexts()
        val dwellEngine = engineWithRealDwell(exitOnlyPolygon())

        // 75 s apart on the boot clock, but the wall clock moved an hour ahead meanwhile.
        val observedVisitId = observeVisitThenExit(dwellEngine, exitTimestampMillis = 3_775_000L, wallClockStepMillis = 3_600_000L)

        coVerify(exactly = 1) {
            emitter.emitWithRetainedAttempt(POLYGON_ID, Event.GeofenceTransition.EXIT, any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        val exit = contexts.filterIsInstance<GeofenceTransitionEmitter.VisitContext.Exit>().single()
        exit.visitId shouldBeEqualTo observedVisitId
        exit.enteredAt.shouldBeNull()
        exit.durationSeconds.shouldBeNull()
        store.getDwellVisit(POLYGON_ID).shouldBeNull()
    }

    @Test
    fun processResponsiveLocation_givenTheWallClockSteppedBackMidVisit_expectVisitEndedAndTheNextOneMeasured() = runTest {
        val contexts = captureVisitContexts()
        val dwellEngine = engineWithRealDwell(exitOnlyPolygon())

        val firstVisitId = observeVisitThenExit(dwellEngine, exitTimestampMillis = 10_000L)

        val firstExit = contexts.filterIsInstance<GeofenceTransitionEmitter.VisitContext.Exit>().single()
        firstExit.visitId shouldBeEqualTo firstVisitId
        firstExit.enteredAt.shouldBeNull()
        firstExit.durationSeconds.shouldBeNull()
        store.getDwellVisit(POLYGON_ID).shouldBeNull()

        // The EXIT's fix proved the device outside, so the next inside fix is an observed entry.
        val reEntryAt = SystemClock.elapsedRealtimeNanos() - 1_000_000_000L
        dwellEngine.processResponsiveLocation(insideFix(elapsedRealtimeNanos = reEntryAt, timestampMillis = 20_000L))
        val second = store.getDwellVisit(POLYGON_ID).shouldNotBeNull()
        second.visitId shouldNotBeEqualTo firstVisitId
        second.entryWasObserved shouldBeEqualTo true
        ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        dwellEngine.processResponsiveLocation(
            fix(37.7750, -122.4175, elapsedRealtimeNanos = reEntryAt + 60_000_000_000L, timestampMillis = 80_000L)
        )

        val exits = contexts.filterIsInstance<GeofenceTransitionEmitter.VisitContext.Exit>()
        exits.map { it.visitId } shouldBeEqualTo listOf(firstVisitId, second.visitId)
        val exit = exits.last()
        exit.visitId shouldBeEqualTo second.visitId
        exit.durationSeconds shouldBeEqualTo 60L
    }

    @Test
    fun processResponsiveLocation_givenANewerVisitWrittenWhileTheExitCommits_expectOnlyTheEndedVisitReportedAndRemoved() = runTest {
        val contexts = captureVisitContexts()
        val region = exitOnlyPolygon()
        val reEntry = AtomicReference<GeofenceDwellVisit>()
        val dwellStore = spyk(store)
        // A re-entry's visit lands at the store boundary, after the EXIT's context was read and
        // before the EXIT's own cleanup.
        every {
            dwellStore.commitBusinessTransition(POLYGON_ID, Event.GeofenceTransition.EXIT, any(), any(), any())
        } answers {
            val committed = callOriginal()
            reEntry.get()?.let { dwellStore.saveDwellVisit(it).shouldBeEqualTo(true) }
            committed
        }
        val dwellEngine = engineWithRealDwell(region, dwellStore)
        val now = SystemClock.elapsedRealtimeNanos()
        dwellEngine.processResponsiveLocation(outsideFix(elapsedRealtimeNanos = now - 3_000_000_000L))
        dwellEngine.processResponsiveLocation(insideFix(elapsedRealtimeNanos = now - 2_000_000_000L))
        val ended = store.getDwellVisit(POLYGON_ID).shouldNotBeNull()
        reEntry.set(
            ended.copy(
                visitId = "re-entry",
                enteredAtSeconds = 175L,
                entryWasObserved = false,
                enteredAtElapsedMs = (now + 74_000_000_000L) / 1_000_000L
            )
        )
        ShadowSystemClock.advanceBy(Duration.ofSeconds(75))

        dwellEngine.processResponsiveLocation(
            fix(37.7750, -122.4175, elapsedRealtimeNanos = now + 73_000_000_000L, timestampMillis = 175_000L)
        )

        val exit = contexts.filterIsInstance<GeofenceTransitionEmitter.VisitContext.Exit>().single()
        exit.visitId shouldBeEqualTo ended.visitId
        exit.durationSeconds shouldBeEqualTo 75L
        store.getDwellVisit(POLYGON_ID)?.visitId shouldBeEqualTo "re-entry"
    }

    private fun captureVisitContexts(): MutableList<GeofenceTransitionEmitter.VisitContext?> {
        val contexts = mutableListOf<GeofenceTransitionEmitter.VisitContext?>()
        coEvery {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), captureNullable(contexts))
        } returns GeofenceTransitionEmitter.Result.PERSISTED
        return contexts
    }

    /**
     * Outside, then inside (wall 100 s), then outside 75 s later on the boot clock. Returns the visit's ID.
     */
    private suspend fun observeVisitThenExit(
        dwellEngine: PolygonLocationEngine,
        exitTimestampMillis: Long,
        wallClockStepMillis: Long = 0L
    ): String {
        val now = SystemClock.elapsedRealtimeNanos()
        dwellEngine.processResponsiveLocation(outsideFix(elapsedRealtimeNanos = now - 3_000_000_000L))
        dwellEngine.processResponsiveLocation(insideFix(elapsedRealtimeNanos = now - 2_000_000_000L))
        val visit = store.getDwellVisit(POLYGON_ID).shouldNotBeNull()
        visit.entryWasObserved shouldBeEqualTo true
        ShadowSystemClock.advanceBy(Duration.ofSeconds(75))
        every { clock.currentTimeMillis() } returns 100_000L + wallClockStepMillis
        dwellEngine.processResponsiveLocation(
            fix(37.7750, -122.4175, elapsedRealtimeNanos = now + 73_000_000_000L, timestampMillis = exitTimestampMillis)
        )
        store.getEnteredIds().shouldBeEmpty()
        return visit.visitId
    }

    private fun exitOnlyPolygon() = polygonRegion().copy(transitionTypes = listOf(GeofenceTransitionType.EXIT))

    @Test
    fun processResponsiveLocation_givenConcurrentDeliveriesOfSameFix_expectSerializedSingleEnter() = runTest {
        val fix = insideFix()

        coroutineScope {
            List(8) { async { engine.processResponsiveLocation(fix) } }.awaitAll()
        }

        store.getEnteredIds() shouldContainSame setOf(POLYGON_ID)
        coVerify(exactly = 1) {
            emitter.emitWithRetainedAttempt(
                any(),
                Event.GeofenceTransition.ENTER,
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun processResponsiveLocation_givenDelayedApproachFix_expectObservedFixCanArmSession() = runTest {
        // Play services batches background deliveries. A fix older than the normal 30-second trigger
        // grace is still valid evidence when the approach session was armed from that same fix.
        val observedAt = SystemClock.elapsedRealtimeNanos() - 50_000_000_000L
        engine.activateFromApproach(POLYGON_ID, observedAt)

        engine.processResponsiveLocation(insideFix(elapsedRealtimeNanos = observedAt))

        store.getEnteredIds() shouldContainSame setOf(POLYGON_ID)
    }

    @Test
    fun processResponsiveLocation_givenDelayedFix_expectTransitionUsesOriginalFixTime() = runTest {
        val observedAt = SystemClock.elapsedRealtimeNanos() - 50_000_000_000L
        every { clock.currentTimeMillis() } returns 200_000L
        engine.activateFromApproach(POLYGON_ID, observedAt)

        engine.processResponsiveLocation(
            insideFix(elapsedRealtimeNanos = observedAt, timestampMillis = 150_000L)
        )

        coVerify(exactly = 1) {
            emitter.emitWithRetainedAttempt(
                POLYGON_ID,
                Event.GeofenceTransition.ENTER,
                "user-1",
                150L,
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        }
    }

    // ---------- best-effort boundary: fixes the engine refuses to decide from ----------

    @Test
    fun processResponsiveLocation_givenAccuracyCircleStraddlingTheRing_expectEnter() = runTest {
        // Arrival applies no departure margin: a missed arrival is never re-derived, while a
        // spurious one is corrected by the next decisive fix.
        engine.processResponsiveLocation(insideFix(accuracyMeters = 45f))

        store.getEnteredIds() shouldBeEqualTo setOf(POLYGON_ID)
    }

    @Test
    fun processResponsiveLocation_givenAccuracyCircleStraddlingTheRingFromOutside_expectNoExit() = runTest {
        // ~13 m outside at 45 m accuracy. Departure keeps the clearance margin so a noisy fix
        // cannot end a visit in progress.
        store.recordEntered(POLYGON_ID)

        engine.processResponsiveLocation(fix(37.7750, -122.41865, accuracyMeters = 45f))

        store.getEnteredIds() shouldBeEqualTo setOf(POLYGON_ID)
        coVerify(exactly = 0) { dwellCoordinator.onInsideEvidence(any(), any(), any(), any()) }
    }

    @Test
    fun processResponsiveLocation_givenFixCoarserThanTheDecisiveCeiling_expectIgnoredAndLogged() = runTest {
        engine.processResponsiveLocation(insideFix(accuracyMeters = 120f))

        store.getEnteredIds().shouldBeEmpty()
        coVerify(exactly = 0) {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun processResponsiveLocation_givenAVenueShallowerThanTheFixAccuracy_expectItStillReports() = runTest {
        // No point in this ring is over 17.6 m from an edge, so a 20 m fix is always marginal;
        // the 50 m ceiling floor keeps it admissible.
        armSmallPolygon()
        val base = SystemClock.elapsedRealtimeNanos() - 10_000_000_000L

        engine.processResponsiveLocation(
            fix(37.7750, -122.4194, elapsedRealtimeNanos = base, accuracyMeters = 20f)
        )
        store.getEnteredIds().shouldBeEmpty()

        engine.processResponsiveLocation(
            fix(37.775018, -122.4194, elapsedRealtimeNanos = base + 5_000_000_000L, accuracyMeters = 20f)
        )

        store.getEnteredIds() shouldBeEqualTo setOf(SMALL_POLYGON_ID)
    }

    @Test
    fun processResponsiveLocation_givenOneMarginalFixThenACoarseOne_expectEnter() = runTest {
        // 5.6 m inside at 18 m is marginal, so it holds. The next fix is too coarse to judge the
        // 54 m ring, so it cannot break the hold.
        val base = SystemClock.elapsedRealtimeNanos() - 10_000_000_000L
        engine.activate(POLYGON_ID)

        engine.processResponsiveLocation(
            fix(37.77455, -122.4194, elapsedRealtimeNanos = base, accuracyMeters = 18f)
        )
        store.getEnteredIds().shouldBeEmpty()

        // About 2 m north, so it is not the held fix re-delivered.
        engine.processResponsiveLocation(
            fix(37.774568, -122.4194, elapsedRealtimeNanos = base + 2_000_000_000L, accuracyMeters = 120f)
        )

        store.getEnteredIds() shouldBeEqualTo setOf(POLYGON_ID)
    }

    @Test
    fun processResponsiveLocation_givenAFixWhoseUncertaintyClearsTheRing_expectEnterFromThatFixAlone() = runTest {
        // 5 m accuracy at 17.6 m clearance cannot have come from outside the ring, so no second fix
        // is needed.
        armSmallPolygon()

        engine.processResponsiveLocation(fix(37.7750, -122.4194, accuracyMeters = 5f))

        store.getEnteredIds() shouldBeEqualTo setOf(SMALL_POLYGON_ID)
    }

    @Test
    fun processResponsiveLocation_givenFixInsideTheTriggerCircleButOutsideTheRing_expectNoEnter() = runTest {
        // Inside the 400 m trigger circle but ~26 m outside the ring: arrival is judged against the
        // ring, never the circle.
        armSmallPolygon()

        engine.processResponsiveLocation(fix(37.7750, -122.41890, accuracyMeters = 20f))

        store.getEnteredIds().shouldBeEmpty()
        coVerify(exactly = 0) {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    private fun armSmallPolygon() {
        store.saveCachedRegions(listOf(smallPolygonRegion()))
        store.saveRegisteredIds(setOf(SMALL_POLYGON_ID))
        store.saveRoutableRegisteredIds(setOf(SMALL_POLYGON_ID))
        store.activatePolygon(SMALL_POLYGON_ID)
        store.recordPolygonCoarseInside(SMALL_POLYGON_ID)
    }

    @Test
    fun processResponsiveLocation_givenVisitEntirelyBetweenTwoFixes_expectMissedRatherThanInvented() = runTest {
        val base = SystemClock.elapsedRealtimeNanos() - 10_000_000_000L

        engine.processResponsiveLocation(fix(37.7750, -122.4175, elapsedRealtimeNanos = base))
        engine.processResponsiveLocation(
            fix(37.7750, -122.4175, elapsedRealtimeNanos = base + 5_000_000_000L)
        )

        store.getEnteredIds().shouldBeEmpty()
        coVerify(exactly = 0) {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun processResponsiveLocation_givenFixWithNoAccuracy_expectIgnoredAndLogged() = runTest {
        val unusable = Location("test").apply {
            latitude = 37.7750
            longitude = -122.4194
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 1_000_000_000L
            time = 100_000L
        }

        engine.processResponsiveLocation(unusable)

        store.getEnteredIds().shouldBeEmpty()
        verify { logger.logPolygonFixNotUsable(any()) }
    }

    // ---------- identity, generation and catalog fencing ----------

    @Test
    fun processResponsiveLocation_givenFixFromPreviousUserGeneration_expectDoesNotAffectNewSession() = runTest {
        val previousGeneration = store.userStateGeneration()
        store.beginUserSession("user-2")
        store.saveRegisteredIds(setOf(POLYGON_ID))
        store.saveRoutableRegisteredIds(setOf(POLYGON_ID))
        store.activatePolygon(POLYGON_ID)
        store.recordPolygonCoarseInside(POLYGON_ID)
        every { secureUserStore.getUserId() } returns "user-2"

        engine.processResponsiveLocation(
            location = insideFix(),
            expectedUserStateGeneration = previousGeneration
        )

        store.getEnteredIds().shouldBeEmpty()
        store.getActivePolygonIds() shouldContainSame setOf(POLYGON_ID)
        coVerify(exactly = 0) {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun processResponsiveLocation_givenRemovalFailedAndOnlyRetainedDefinitionExists_expectDoesNotEvaluateRetiredPolygon() = runTest {
        store.saveCachedRegions(emptyList())
        store.saveRetainedRegisteredRegions(listOf(polygonRegion()))

        engine.processResponsiveLocation(insideFix())

        store.getEnteredIds().shouldBeEmpty()
        coVerify(exactly = 0) {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun processResponsiveLocation_givenEnclosingCircleEditedButRingUnchanged_expectTransitionStillCommitted() = runTest {
        // The transition revision covers the enclosing circle too. A fence cache keyed on the ring
        // alone would serve the old revision, which the processor drops as replaced geometry.
        engine.processResponsiveLocation(insideFix())
        store.getEnteredIds() shouldContainSame setOf(POLYGON_ID)
        store.saveCachedRegions(listOf(polygonRegion().copy(radius = 900f)))

        engine.processResponsiveLocation(outsideFix(elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()))

        store.getEnteredIds().shouldBeEmpty()
        coVerify(exactly = 1) {
            emitter.emitWithRetainedAttempt(
                POLYGON_ID,
                Event.GeofenceTransition.EXIT,
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun processResponsiveLocation_givenCachedRingThatNoLongerValidates_expectPolygonSkippedNotTreatedAsItsCircle() = runTest {
        // Self-intersecting ring: the region still carries a wide enclosing trigger circle, and the fix
        // is well inside that circle. Falling back to it would report ENTER for the wrong area.
        store.saveCachedRegions(
            listOf(
                GeofenceRegion(
                    id = POLYGON_ID,
                    latitude = 37.7750,
                    longitude = -122.4194,
                    radius = 5_000f,
                    polygonVertices = listOf(
                        PolygonCoordinate(37.7745, -122.4200),
                        PolygonCoordinate(37.7755, -122.4188),
                        PolygonCoordinate(37.7745, -122.4188),
                        PolygonCoordinate(37.7755, -122.4200)
                    )
                )
            )
        )
        engine.resetEvidence(POLYGON_ID)

        engine.processResponsiveLocation(insideFix())

        store.getEnteredIds().shouldBeEmpty()
        coVerify(exactly = 0) {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun processResponsiveLocation_givenOlderStageStillUnavailable_expectNewEdgeDoesNotOvertakeIt() = runTest {
        coEvery { emitter.recoverPendingTransitions() } returns false

        engine.processResponsiveLocation(insideFix())

        store.getEnteredIds().shouldBeEmpty()
        coVerify(exactly = 0) {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun resetEvidence_givenInactiveEditThenLaterActivation_expectPreActivationFixesRejected() = runTest {
        store.deactivatePolygon(POLYGON_ID)
        engine.stop()
        val beforeActivation = SystemClock.elapsedRealtimeNanos()

        engine.resetEvidence(POLYGON_ID)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        store.activatePolygon(POLYGON_ID)
        engine.activate(POLYGON_ID)
        engine.processResponsiveLocation(insideFix(elapsedRealtimeNanos = beforeActivation))

        store.getEnteredIds().shouldBeEmpty()
        coVerify(exactly = 0) {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        // Explicit values because `any()` would also match the default nulls.
        verify {
            logger.logPolygonFixNotUsable(
                PolygonFixRejection.FIX_TOO_OLD,
                horizontalAccuracyMeters = 5.0,
                fixAgeSeconds = match { it != null && it >= 60.0 }
            )
        }
    }

    // ---------- exits ----------

    @Test
    fun processResponsiveLocation_givenCoarseExitThenDecisivePolygonExit_expectSessionTerminates() = runTest {
        store.recordEntered(POLYGON_ID)
        store.recordPolygonCoarseOutside(POLYGON_ID)

        engine.processResponsiveLocation(outsideFix())

        store.getEnteredIds().shouldBeEmpty()
        store.getActivePolygonIds().shouldBeEmpty()
        coVerify(exactly = 1) {
            emitter.emitWithRetainedAttempt(
                any(),
                Event.GeofenceTransition.EXIT,
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun processResponsiveLocation_givenFineExitCannotPersist_expectSessionRemainsActiveForRetry() = runTest {
        store.recordEntered(POLYGON_ID)
        store.recordPolygonCoarseOutside(POLYGON_ID)
        coEvery {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns GeofenceTransitionEmitter.Result.PERSIST_FAILED

        engine.processResponsiveLocation(outsideFix())

        store.getEnteredIds() shouldContainSame setOf(POLYGON_ID)
        store.getActivePolygonIds() shouldContainSame setOf(POLYGON_ID)
    }

    private fun insideFix(
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos() - 2_000_000_000L,
        timestampMillis: Long = 100_000L,
        accuracyMeters: Float = 5f
    ) = fix(37.7750, -122.4194, elapsedRealtimeNanos, timestampMillis, accuracyMeters)

    private fun marginalInsideFix(elapsedRealtimeNanos: Long) =
        fix(37.775455, -122.4194, elapsedRealtimeNanos, accuracyMeters = 40f)

    private fun outsideFix(
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos() - 2_000_000_000L
    ) = fix(37.7750, -122.4175, elapsedRealtimeNanos)

    private fun fix(
        latitude: Double,
        longitude: Double,
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos() - 2_000_000_000L,
        timestampMillis: Long = 100_000L,
        accuracyMeters: Float = 5f
    ) = Location("test").apply {
        this.latitude = latitude
        this.longitude = longitude
        accuracy = accuracyMeters
        this.elapsedRealtimeNanos = elapsedRealtimeNanos
        time = timestampMillis
    }

    // Roughly 110 m x 105 m, so its centre sits ~52 m from the nearest edge.
    private fun polygonRegion() = GeofenceRegion(
        id = POLYGON_ID,
        latitude = 37.7750,
        longitude = -122.4194,
        radius = 200f,
        polygonVertices = listOf(
            PolygonCoordinate(37.7745, -122.4200),
            PolygonCoordinate(37.7745, -122.4188),
            PolygonCoordinate(37.7755, -122.4188),
            PolygonCoordinate(37.7755, -122.4200)
        )
    )

    // Roughly 39 m x 35 m, so its centre is under 20 m from the nearest edge.
    private fun smallPolygonRegion() = GeofenceRegion(
        id = SMALL_POLYGON_ID,
        latitude = 37.7750,
        longitude = -122.4194,
        radius = 400f,
        polygonVertices = listOf(
            PolygonCoordinate(37.77482, -122.41960),
            PolygonCoordinate(37.77482, -122.41920),
            PolygonCoordinate(37.77517, -122.41920),
            PolygonCoordinate(37.77517, -122.41960)
        )
    )

    private companion object {
        const val POLYGON_ID = "campus"
        const val SMALL_POLYGON_ID = "retail-unit"
    }
}
