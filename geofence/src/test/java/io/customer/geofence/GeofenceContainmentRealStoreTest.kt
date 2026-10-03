package io.customer.geofence

import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.api.GeofenceApiService
import io.customer.geofence.store.GeofenceRegionStoreImpl
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Clock
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContainSame
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
            packageInfo = packageInfo,
            logger = mockk(relaxed = true)
        )
        // State a successful anchor pass leaves behind: registered and stamped, containment unjudged.
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

        val coordinator = GeofenceDwellCoordinator(store, processor)
        coordinator.onEnter(dwellFence.id, enteredAtSeconds = 900L, entryFixElapsedMs = 12_000L)
        coordinator.onNativeDwell(dwellFence.id, observedAtSeconds = 960L, triggeringFixElapsedMs = 72_000L)

        coVerify(exactly = 2) {
            processor.process(any(), Event.GeofenceTransition.DWELL, any(), any(), any(), any(), any(), any(), any())
        }
        store.getDwellVisit(dwellFence.id)?.entryWasObserved shouldBeEqualTo true
    }

    @Test
    fun lostExitThenEnterWithoutOutsideProof_expectSingleDwell() = runTest {
        val processor = lostExitVisit()
        val coordinator = GeofenceDwellCoordinator(store, processor)

        coordinator.onEnter(dwellFence.id, enteredAtSeconds = 900L, entryFixElapsedMs = 12_000L)
        coordinator.onNativeDwell(dwellFence.id, observedAtSeconds = 960L, triggeringFixElapsedMs = 72_000L)

        coVerify(exactly = 1) {
            processor.process(any(), Event.GeofenceTransition.DWELL, any(), any(), any(), any(), any(), any(), any())
        }
    }

    /** A circle visit entered at fix 2 000 and dwelled at 5 000, whose EXIT was then lost. */
    private suspend fun lostExitVisit(): GeofenceBusinessTransitionProcessor {
        store.saveCachedRegions(listOf(dwellFence))
        store.recordRegistrationIncarnations(listOf(dwellFence), registeredAtElapsedMs = 1_000L)
        store.recordEntered(dwellFence.id)
        val processor = mockk<GeofenceBusinessTransitionProcessor>()
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        val coordinator = GeofenceDwellCoordinator(store, processor)
        coordinator.onEnter(dwellFence.id, enteredAtSeconds = 100L, beginsNewVisit = true, entryFixElapsedMs = 2_000L)
        coordinator.onNativeDwell(dwellFence.id, observedAtSeconds = 160L, triggeringFixElapsedMs = 5_000L)
        store.getDwellVisit(dwellFence.id)?.emitted shouldBeEqualTo true
        return processor
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
