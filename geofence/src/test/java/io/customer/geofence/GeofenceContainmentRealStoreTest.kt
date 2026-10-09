package io.customer.geofence

import android.location.Location
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.api.GeofenceApiService
import io.customer.geofence.polygon.PolygonCoordinate
import io.customer.geofence.polygon.PolygonSupport
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldContainSame
import org.amshove.kluent.shouldNotBeNull
import org.amshove.kluent.shouldNotContain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Runs the containment-only pass against the real [GeofenceRegionStoreImpl]; only its epoch filter
 * and key-absence grace can show this path arms the EXIT guard.
 */
@RunWith(RobolectricTestRunner::class)
class GeofenceContainmentRealStoreTest : RobolectricTest() {

    private lateinit var store: GeofenceRegionStoreImpl
    private lateinit var repository: GeofenceRepositoryImpl

    private val manager: GeofenceManager = mockk(relaxed = true)
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val clock: Clock = mockk(relaxed = true)
    private val packageInfo: GeofencePackageInfo = mockk { every { lastUpdateTimeMs() } returns null }

    private var bootSessionId = "boot"
    private val fence = GeofenceRegion("biz-1", 0.0, 0.0, 100f)
    private val dwellFence = fence.copy(dwellThresholdSeconds = 60)

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { argument(ApplicationArgument(applicationMock)) })
        store = GeofenceRegionStoreImpl(
            context = applicationMock,
            jsonSerializer = GeofenceJsonSerializer(),
            logger = mockk(relaxed = true)
        )
        store.clearAll()
        every { secureUserStore.getUserId() } returns "user-42"
        every { clock.elapsedRealtime() } returns 10_000L
        every { clock.currentTimeMillis() } returns System.currentTimeMillis()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)
        repository = GeofenceRepositoryImpl(
            apiService = mockk<GeofenceApiService>(relaxed = true),
            store = store,
            distanceFilter = GeofenceDistanceFilter(),
            manager = manager,
            secureUserStore = secureUserStore,
            cooldownFilter = mockk(relaxed = true),
            transitionEmitter = mockk(relaxed = true),
            clock = clock,
            bootSessionProvider = { bootSessionId },
            packageInfo = packageInfo,
            logger = mockk(relaxed = true)
        )
        seedAnchorPass(bootStamp = bootSessionId)
    }

    @Test
    fun liveFixAfterAMissedBootCompleted_givenUptimePastTheLastRegistration_expectEveryFenceReRegistered() = runTest {
        // Rebooted without BOOT_COMPLETED reaching the app, and up longer than the last
        // registration's stamp, so only the boot identity shows GMS dropped everything.
        val queued = PendingGeofenceDelivery(
            geofenceId = fence.id,
            transition = Event.GeofenceTransition.ENTER,
            timestamp = 100L,
            userId = "user-42",
            transitionId = "queued"
        )
        store.savePendingTransitionEntries(listOf(queued), store.userStateGeneration()).shouldBeTrue()
        bootSessionId = "boot-b"
        every { clock.elapsedRealtime() } returns 900_000L

        repository.refreshFromLiveFix(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 1) { manager.replaceGeofences(any(), emptySet()) }
        store.getLastRegistrationBootSession() shouldBeEqualTo "boot-b"
        store.getAllPendingTransitionEntries() shouldBeEqualTo listOf(queued)
    }

    @Test
    fun liveFixOnARegistrationWithoutABootStamp_expectEveryFenceReRegistered() = runTest {
        // Registered by a version that predates the stamp, so nothing shows it is from this boot.
        store.clearAll()
        seedAnchorPass(bootStamp = null)

        repository.refreshFromLiveFix(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 1) { manager.replaceGeofences(any(), emptySet()) }
    }

    @Test
    fun anchorOnlyState_expectGuardStillDeferring() {
        store.hasContainmentRecord().shouldBeFalse()
        store.claimExit(fence.id).shouldBeFalse()
    }

    @Test
    fun liveFixOnFreshInputs_expectGuardArmedAndGenuineExitClaimable() = runTest {
        repository.refreshFromLiveFix(latitude = 0.0, longitude = 0.0)

        // Pins the SKIP path; a LOCAL pass would also satisfy the assertions below.
        coVerify(exactly = 0) { manager.replaceGeofences(any(), any()) }
        store.getEnteredIds() shouldContainSame setOf(fence.id)
        store.hasContainmentRecord().shouldBeTrue()
        store.claimExit(fence.id).shouldBeTrue()
    }

    @Test
    fun liveFixOutsideEveryFence_expectRecordCreatedEmpty() = runTest {
        // ~555m out: inside nothing must still end the grace.
        repository.refreshFromLiveFix(latitude = 0.005, longitude = 0.0)

        store.getEnteredIds() shouldBeEqualTo emptySet()
        store.hasContainmentRecord().shouldBeTrue()
    }

    @Test
    fun exitClaimedBeforeTheFix_expectTheFixReseeds() = runTest {
        // A claim before the pass captured its epoch is older evidence, so the fix re-seeds.
        store.recordEntered(fence.id)
        store.claimExit(fence.id).shouldBeTrue()

        repository.refreshFromLiveFix(latitude = 0.0, longitude = 0.0)

        store.getEnteredIds() shouldContainSame setOf(fence.id)
    }

    @Test
    fun lostExitThenMovementProofThenEnter_expectSecondVisitDwells() = runTest {
        val processor = lostExitVisit()

        // The device left (EXIT lost) and travelled far enough to trip the movement trigger. That
        // pass keeps the fence registered, and its fix clears the edge by more than its accuracy.
        repository.handleMovement(
            latitude = 0.003,
            longitude = 0.0,
            movementTriggerRadius = { null },
            fixQuality = GeofenceFixQuality(fixElapsedRealtimeMillis = 9_000L, horizontalAccuracyMeters = 30f)
        )
        store.getRegistrationIncarnation(dwellFence.id)?.registeredAtElapsedMs shouldBeEqualTo 1_000L
        store.getRegistrationIncarnation(dwellFence.id)?.outsideProvenAtElapsedMs shouldBeEqualTo 9_000L
        // The premise: nothing on this path retires the stale containment record.
        store.getEnteredIds() shouldContainSame setOf(dwellFence.id)

        val coordinator = GeofenceDwellCoordinator(store, processor, clock) { "boot" }
        coordinator.onEnter(dwellFence.id, enteredAtSeconds = 900L, entryFixElapsedMs = 12_000L)
        coordinator.onNativeDwell(dwellFence.id, observedAtSeconds = 960L, triggeringFixElapsedMs = 72_000L)

        coVerify(exactly = 2) {
            processor.process(any(), Event.GeofenceTransition.DWELL, any(), any(), any(), any(), any(), any(), any())
        }
        store.getDwellVisit(dwellFence.id)?.entryWasObserved shouldBeEqualTo true
    }

    @Test
    fun movementProofThenGmsDwellThenReEnter_expectTheDwellIgnoredAndTheReEntryDwells() = runTest {
        val processor = lostExitVisit()
        // The kept fence's movement fix proves the device left, as above; GMS reports no EXIT.
        repository.handleMovement(
            latitude = 0.003,
            longitude = 0.0,
            movementTriggerRadius = { null },
            fixQuality = GeofenceFixQuality(fixElapsedRealtimeMillis = 9_000L, horizontalAccuracyMeters = 30f)
        )
        store.getRegistrationIncarnation(dwellFence.id)?.outsideProvenAtElapsedMs shouldBeEqualTo 9_000L
        val coordinator = GeofenceDwellCoordinator(store, processor, clock) { "boot" }

        // GMS never noticed the departure, so this DWELL may still describe the first stay.
        coordinator.onNativeDwell(dwellFence.id, observedAtSeconds = 960L, triggeringFixElapsedMs = 72_000L)
        coordinator.onEnter(dwellFence.id, enteredAtSeconds = 980L, entryFixElapsedMs = 80_000L)
        coordinator.onNativeDwell(dwellFence.id, observedAtSeconds = 1_040L, triggeringFixElapsedMs = 140_000L)

        coVerify(exactly = 2) {
            processor.process(any(), Event.GeofenceTransition.DWELL, any(), any(), any(), any(), any(), any(), any())
        }
        store.getDwellVisit(dwellFence.id)?.entryFixElapsedMs shouldBeEqualTo 80_000L
    }

    @Test
    fun movementFixClearingTheEdgeWhenRegistrationFails_expectProofKeptAndNoDwellAcrossTheAbsence() = runTest {
        val world = provenOutsideWorld()
        world.pipeline.handle(nativeCrossing(GeofenceCrossingTransition.ENTER, fixMs = 2_000L))
        val visitId = store.getDwellVisit(dwellFence.id).shouldNotBeNull().visitId
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.failure(IllegalStateException("GMS add failed"))

        // The fix GMS delivered with the movement EXIT lies ~233 m past the edge at 30 m accuracy.
        repository.handleMovement(0.003, 0.0, { null }, GeofenceFixQuality(fixElapsedRealtimeMillis = 9_000L, horizontalAccuracyMeters = 30f))

        store.getRegistrationIncarnation(dwellFence.id)?.outsideProvenAtElapsedMs shouldBeEqualTo 9_000L
        // GMS reported neither the departure nor a return, so its DWELL may count from ENTER@2000.
        world.pipeline.handle(nativeCrossing(GeofenceCrossingTransition.DWELL, fixMs = 72_000L))

        world.outbox.loadAll().filter { it.transition == Event.GeofenceTransition.DWELL } shouldBeEqualTo emptyList()
        val visit = store.getDwellVisit(dwellFence.id).shouldNotBeNull()
        visit.visitId shouldBeEqualTo visitId
        visit.entryWasObserved shouldBeEqualTo false
        store.getEnteredIds() shouldContainSame setOf(dwellFence.id)
    }

    @Test
    fun movementFixClearingTheEdgeWhileTheRefreshSlotIsBusy_expectProofKept() = runTest {
        val world = provenOutsideWorld()
        world.pipeline.handle(nativeCrossing(GeofenceCrossingTransition.ENTER, fixMs = 2_000L))
        val held = CompletableDeferred<Unit>()
        coEvery { manager.replaceGeofences(any(), any()) } coAnswers {
            held.await()
            Result.failure(IllegalStateException("GMS add failed"))
        }
        // An earlier pass holds the refresh slot for the whole wait.
        val busy = launch(start = CoroutineStart.UNDISPATCHED) {
            repository.handleMovement(0.0, 0.0, { null }, GeofenceFixQuality.UNKNOWN)
        }

        repository.handleMovement(0.003, 0.0, { null }, GeofenceFixQuality(fixElapsedRealtimeMillis = 9_000L, horizontalAccuracyMeters = 30f))

        store.getRegistrationIncarnation(dwellFence.id)?.outsideProvenAtElapsedMs shouldBeEqualTo 9_000L
        held.complete(Unit)
        busy.join()
        world.pipeline.handle(nativeCrossing(GeofenceCrossingTransition.DWELL, fixMs = 72_000L))
        world.outbox.loadAll().filter { it.transition == Event.GeofenceTransition.DWELL } shouldBeEqualTo emptyList()
    }

    @Test
    fun movementFixThatCannotProveTheDeviceOutside_expectNoProof() = runTest {
        // Controls for the early proof: each of these proves nothing about the registered circle.
        provenOutsideWorld()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.failure(IllegalStateException("GMS add failed"))
        data class Case(
            val name: String,
            val latitude: Double,
            val quality: GeofenceFixQuality,
            val registeredAt: Long = 1_000L,
            val registeredInBoot: String = "boot"
        )
        val clear = GeofenceFixQuality(fixElapsedRealtimeMillis = 9_000L, horizontalAccuracyMeters = 30f)
        val cases = listOf(
            Case("inside", 0.0, clear),
            // ~40 m past the edge, within the 30 m accuracy plus the 20 m margin.
            Case("marginal", 0.00126, clear),
            Case("no accuracy", 0.003, GeofenceFixQuality(fixElapsedRealtimeMillis = 9_000L)),
            Case("no fix time", 0.003, GeofenceFixQuality(horizontalAccuracyMeters = 30f)),
            // Stamped after now (10 000) on the boot clock, so its time is not trustworthy.
            Case("future fix", 0.003, clear.copy(fixElapsedRealtimeMillis = 20_000L)),
            // GMS was not watching this registration yet when the fix was taken.
            Case("registered after the fix", 0.003, clear, registeredAt = 9_500L),
            Case("registered in another boot", 0.003, clear, registeredInBoot = "boot-old")
        )

        val proofs = cases.associate { case ->
            store.recordRegistrationIncarnations(listOf(dwellFence), case.registeredAt, case.registeredInBoot)
            repository.handleMovement(case.latitude, 0.0, { null }, case.quality)
            case.name to store.getRegistrationIncarnation(dwellFence.id)?.outsideProvenAtElapsedMs
        }

        proofs shouldBeEqualTo cases.associate { it.name to null }
    }

    /** Registered at 1 000 with the device proven outside then, a session, and a real outbox. */
    private fun provenOutsideWorld(): ProofWorld {
        store.beginUserSession("user-42")
        seedAnchorPass(bootStamp = bootSessionId)
        store.saveCachedRegions(listOf(dwellFence))
        store.recordRegistrationIncarnations(listOf(dwellFence), 1_000L, "boot", outsideIds = setOf(dwellFence.id))
        val outbox = PendingDeliveryStore(
            context = applicationMock,
            fileName = "cio_test_movement_proof_outbox.json",
            elementSerializer = PendingGeofenceDelivery.serializer(),
            logger = mockk(relaxed = true)
        ).also { it.removeAll() }
        val cooldownFilter: GeofenceCooldownFilter = mockk(relaxed = true) {
            every { suppressedForSeconds(any(), any(), any()) } returns null
        }
        val processor = GeofenceBusinessTransitionProcessor(
            store,
            secureUserStore,
            GeofenceTransitionEmitter(cooldownFilter, outbox, mockk(relaxed = true), store, mockk(relaxed = true)),
            mockk(relaxed = true)
        )
        val pipeline = GeofenceCrossingPipeline(
            regionStore = store,
            services = mockk(relaxed = true),
            registrar = mockk(relaxed = true),
            transitionProcessor = processor,
            dwellCoordinator = GeofenceDwellCoordinator(store, processor, clock) { "boot" },
            polygonController = mockk(relaxed = true),
            logger = mockk(relaxed = true)
        )
        return ProofWorld(pipeline, outbox)
    }

    private class ProofWorld(
        val pipeline: GeofenceCrossingPipeline,
        val outbox: PendingDeliveryStore<PendingGeofenceDelivery>
    )

    private fun nativeCrossing(transition: GeofenceCrossingTransition, fixMs: Long) = GeofenceCrossing(
        geofenceIds = listOf(dwellFence.id),
        transition = transition,
        transitionName = transition.name,
        rawTransitionCode = 0,
        latitude = 0.0,
        longitude = 0.0,
        triggeringLocation = Location("gps").apply {
            accuracy = 10f
            elapsedRealtimeNanos = fixMs * 1_000_000L
        },
        receivedAtSeconds = 1_000L + (fixMs + 100L) / 1_000L,
        receivedAtElapsedMs = fixMs + 100L
    )

    @Test
    fun lostExitThenEnterWithoutOutsideProof_expectSingleDwell() = runTest {
        val processor = lostExitVisit()
        val coordinator = GeofenceDwellCoordinator(store, processor, clock) { "boot" }

        coordinator.onEnter(dwellFence.id, enteredAtSeconds = 900L, entryFixElapsedMs = 12_000L)
        coordinator.onNativeDwell(dwellFence.id, observedAtSeconds = 960L, triggeringFixElapsedMs = 72_000L)

        coVerify(exactly = 1) {
            processor.process(any(), Event.GeofenceTransition.DWELL, any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun polygonUnregisteredThenReRegistered_expectNoVisitCarriedAcrossTheGap() = runTest {
        val polygon = GeofenceRegion(
            id = "poly-1",
            latitude = 0.0,
            longitude = 0.0,
            radius = 200f,
            polygonVertices = listOf(
                PolygonCoordinate(-0.001, -0.001),
                PolygonCoordinate(-0.001, 0.001),
                PolygonCoordinate(0.001, 0.001),
                PolygonCoordinate(0.001, -0.001),
                PolygonCoordinate(-0.001, -0.001)
            ),
            dwellThresholdSeconds = 60
        )
        store.saveCachedRegions(listOf(fence, polygon))
        store.saveRegisteredIds(setOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, fence.id, polygon.id))
        store.saveRoutableRegisteredIds(setOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, fence.id, polygon.id))
        // The polygon engine committed the device inside and observed the entry.
        store.recordEntered(polygon.id)
        val processor = mockk<GeofenceBusinessTransitionProcessor>()
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        val coordinator = GeofenceDwellCoordinator(store, processor, clock) { "boot" }
        coordinator.onEnter(
            polygon.id,
            enteredAtSeconds = 1_000L,
            beginsNewVisit = true,
            polygonOutsideObserved = true,
            enteredAtElapsedMs = 20_000L
        )
        val endedVisit = store.getDwellVisit(polygon.id).shouldNotBeNull()
        val polygonRepository = GeofenceRepositoryImpl(
            apiService = mockk<GeofenceApiService>(relaxed = true),
            store = store,
            distanceFilter = GeofenceDistanceFilter(polygonSupport = PolygonSupport.Enabled),
            manager = manager,
            secureUserStore = secureUserStore,
            cooldownFilter = mockk(relaxed = true),
            transitionEmitter = mockk(relaxed = true),
            clock = clock,
            bootSessionProvider = { "boot" },
            packageInfo = packageInfo,
            logger = mockk(relaxed = true),
            polygonSupport = PolygonSupport.Enabled
        )

        // The kill switch unregisters every fence; lifting it registers the same polygon again.
        store.saveCachedConfig(sampleConfig().copy(maxBusinessGeofences = 0))
        polygonRepository.handleMovement(0.0, 0.0, { null }, GeofenceFixQuality.UNKNOWN)
        store.getRoutableRegisteredIds() shouldNotContain polygon.id

        store.getDwellVisit(polygon.id).shouldBeNull()

        store.saveCachedConfig(sampleConfig())
        polygonRepository.handleMovement(0.0, 0.0, { null }, GeofenceFixQuality.UNKNOWN)
        store.getRoutableRegisteredIds() shouldContain polygon.id
        // An emission prepared before the gap lands only now.
        store.saveDwellVisit(endedVisit.copy(emitted = true)).shouldBeFalse()
        // Monitoring resumed with the device inside, so its arrival time is unknown.
        store.recordEntered(polygon.id)
        coordinator.onEnter(polygon.id, enteredAtSeconds = 1_030L, beginsNewVisit = true, enteredAtElapsedMs = 50_000L)

        val visit = store.getDwellVisit(polygon.id).shouldNotBeNull()
        (visit.visitId == endedVisit.visitId) shouldBeEqualTo false
        visit.enteredAtSeconds shouldBeEqualTo 1_030L
        visit.entryWasObserved shouldBeEqualTo false
    }

    /** A circle visit entered at fix 2 000 and dwelled at 5 000, whose EXIT was then lost. */
    private suspend fun lostExitVisit(): GeofenceBusinessTransitionProcessor {
        store.saveCachedRegions(listOf(dwellFence))
        store.recordRegistrationIncarnations(listOf(dwellFence), registeredAtElapsedMs = 1_000L, bootSessionId = "boot")
        store.recordEntered(dwellFence.id)
        val processor = mockk<GeofenceBusinessTransitionProcessor>()
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        val coordinator = GeofenceDwellCoordinator(store, processor, clock) { "boot" }
        coordinator.onEnter(dwellFence.id, enteredAtSeconds = 100L, beginsNewVisit = true, entryFixElapsedMs = 2_000L)
        coordinator.onNativeDwell(dwellFence.id, observedAtSeconds = 160L, triggeringFixElapsedMs = 5_000L)
        store.getDwellVisit(dwellFence.id)?.emitted shouldBeEqualTo true
        return processor
    }

    /** State a successful anchor pass leaves behind: registered and stamped, containment unjudged. */
    private fun seedAnchorPass(bootStamp: String?) {
        store.saveCachedRegions(listOf(fence))
        store.saveCachedConfig(sampleConfig())
        store.saveApiFetchStateIfCurrent(
            location = GeofenceLocation(0.0, 0.0),
            syncTimestamp = System.currentTimeMillis(),
            expectedUserStateGeneration = store.userStateGeneration()
        )
        store.saveRegisteredIds(setOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, fence.id))
        // Without routing the cache reads as unregistered and the refresh re-registers instead.
        store.saveRoutableRegisteredIds(setOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, fence.id))
        store.saveLastMovementTriggerLocation(GeofenceLocation(0.0, 0.0), 1_000f)
        store.setLastRegistrationUptime(clock.elapsedRealtime())
        bootStamp?.let(store::setLastRegistrationBootSession)
    }

    private fun sampleConfig() = GeofenceConfig(
        localRefreshTriggerRadius = 1_000f,
        remoteFetchRefreshTriggerRadius = 5_000f,
        remoteFetchRefreshExpiry = 86_400_000L,
        duplicateEventsExpiry = 3_600_000L,
        maxBusinessGeofences = 19,
        maxMonitoringDistance = GeofenceConstants.NO_MONITORING_DISTANCE_CAP_METERS
    )
}
