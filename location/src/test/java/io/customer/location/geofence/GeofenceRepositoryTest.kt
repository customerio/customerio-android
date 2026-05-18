package io.customer.location.geofence

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.location.geofence.api.GeofenceApiResponse
import io.customer.location.geofence.api.GeofenceApiService
import io.customer.location.geofence.store.GeofenceRegionStore
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContainSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceRepositoryTest : RobolectricTest() {

    private val apiService: GeofenceApiService = mockk(relaxed = true)
    private val store: GeofenceRegionStore = mockk(relaxed = true)
    private val distanceFilter: GeofenceDistanceFilter = mockk(relaxed = true)
    private val manager: GeofenceManager = mockk(relaxed = true)
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val logger: GeofenceLogger = mockk(relaxed = true)
    private val jsonSerializer = GeofenceJsonSerializer()

    private lateinit var repository: GeofenceRepositoryImpl

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { })
        repository = GeofenceRepositoryImpl(
            apiService = apiService,
            store = store,
            distanceFilter = distanceFilter,
            manager = manager,
            secureUserStore = secureUserStore,
            logger = logger
        )
    }

    @Test
    fun refresh_givenRecentSuccessfulSyncAndNotForced_expectSkipApiCall() = runTest {
        // Repeated identify within the freshness window must not hit the API.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L // 1 min ago

        val result = repository.refresh(latitude = 0.0, longitude = 0.0)

        result.isSuccess shouldBeEqualTo true
        coVerify(exactly = 0) { apiService.fetchGeofences(any(), any(), any()) }
        verify { logger.logSyncSkippedFresh() }
    }

    @Test
    fun refresh_givenStaleLastSync_expectApiCalled() = runTest {
        // Last sync older than threshold => proceed with refresh.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns
            System.currentTimeMillis() - GeofenceConstants.STALE_THRESHOLD_MS - 1_000L
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any(), any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.addGeofences(any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify { apiService.fetchGeofences(any(), any(), any()) }
    }

    @Test
    fun refresh_givenNoPreviousSync_expectApiCalled() = runTest {
        // First-ever sync: no timestamp, threshold check is a no-op, proceed.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns null
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any(), any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.addGeofences(any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify { apiService.fetchGeofences(any(), any(), any()) }
    }

    @Test
    fun refresh_givenNoUserId_expectSkipAndSuccessAndNoApiCall() = runTest {
        every { secureUserStore.getUserId() } returns null

        val result = repository.refresh(latitude = 12.34, longitude = 56.78)

        result.isSuccess shouldBeEqualTo true
        coVerify(exactly = 0) { apiService.fetchGeofences(any(), any(), any()) }
        coVerify(exactly = 0) { manager.addGeofences(any()) }
        verify(exactly = 0) { store.saveRegisteredIds(any()) }
        verify { logger.logSyncSkipped(match { it.contains("no identified user") }) }
    }

    @Test
    fun refresh_givenBlankUserId_expectSkipAndSuccess() = runTest {
        every { secureUserStore.getUserId() } returns "   "

        val result = repository.refresh(latitude = 0.0, longitude = 0.0)

        result.isSuccess shouldBeEqualTo true
        coVerify(exactly = 0) { apiService.fetchGeofences(any(), any(), any()) }
    }

    @Test
    fun refresh_givenApiFailure_expectFailurePropagatedAndNoPersistOrRegister() = runTest {
        val error = IOException("network down")
        every { secureUserStore.getUserId() } returns "user-42"
        coEvery { apiService.fetchGeofences(any(), any(), any()) } returns Result.failure(error)

        val result = repository.refresh(latitude = 12.34, longitude = 56.78)

        result.isFailure shouldBeEqualTo true
        result.exceptionOrNull() shouldBeEqualTo error
        verify { logger.logSyncFailed(match { it?.contains("network down") == true }) }
        verify(exactly = 0) { store.saveRegisteredIds(any()) }
        verify(exactly = 0) { store.setLastSyncTimestamp(any()) }
        coVerify(exactly = 0) { manager.addGeofences(any()) }
    }

    @Test
    fun refresh_givenBusinessGeofences_expectMovementTriggerPrependedAndRegistered() = runTest {
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        val filtered = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { apiService.fetchGeofences("user-42", 12.34, 56.78) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3, localRefreshTriggerRadius = 1500f))
        every { distanceFilter.nearest(any(), 12.34, 56.78, 3) } returns filtered
        val captured = slot<List<GeofenceRegion>>()
        coEvery { manager.addGeofences(capture(captured)) } returns Result.success(Unit)

        val result = repository.refresh(latitude = 12.34, longitude = 56.78)

        result.isSuccess shouldBeEqualTo true
        verify { store.setLastSyncTimestamp(any()) }
        verify { distanceFilter.nearest(any(), 12.34, 56.78, 3) }
        verify { logger.logSyncSucceeded(filtered.size) }
        // Store holds the IDs of exactly what was registered (movement trigger + business),
        // so the next refresh's stale-cleanup diff is accurate.
        verify { store.saveRegisteredIds(captured.captured.map { it.id }.toSet()) }

        // Movement trigger is prepended with config's radius, centered on the request location,
        // and only listens for EXIT (so we re-fetch when the user leaves this area).
        captured.captured.size shouldBeEqualTo 2
        val movementTrigger = captured.captured[0]
        movementTrigger.id shouldBeEqualTo GeofenceConstants.MOVEMENT_TRIGGER_ID
        movementTrigger.latitude shouldBeEqualTo 12.34
        movementTrigger.longitude shouldBeEqualTo 56.78
        movementTrigger.radius shouldBeEqualTo 1500f
        movementTrigger.transitionTypes shouldBeEqualTo listOf(GeofenceTransitionType.EXIT)
        captured.captured[1] shouldBeEqualTo filtered[0]
    }

    @Test
    fun refresh_givenSuccess_expectCacheAndConfigAndAnchorPersisted() = runTest {
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences("user-42", 12.34, 56.78) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3, localRefreshTriggerRadius = 1500f))
        every { distanceFilter.nearest(any(), 12.34, 56.78, 3) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { manager.addGeofences(any()) } returns Result.success(Unit)
        val regionsSlot = slot<List<GeofenceRegion>>()
        val configSlot = slot<GeofenceConfig>()
        val anchorSlot = slot<GeofenceLocation>()
        every { store.saveCachedRegions(capture(regionsSlot)) } returns Unit
        every { store.saveCachedConfig(capture(configSlot)) } returns Unit
        every { store.saveLastApiFetchLocation(capture(anchorSlot)) } returns Unit

        repository.refresh(latitude = 12.34, longitude = 56.78)

        // Cache stores the FULL backend response (from sampleResponse), not the
        // distance-filter output — the tier-A re-rank needs the unfiltered set.
        regionsSlot.captured.map { it.id } shouldBeEqualTo listOf("g-1")
        configSlot.captured.localRefreshTriggerRadius shouldBeEqualTo 1500f
        anchorSlot.captured shouldBeEqualTo GeofenceLocation(latitude = 12.34, longitude = 56.78)
    }

    @Test
    fun refresh_givenManagerFails_expectCacheAndAnchorNotPersisted() = runTest {
        // Symmetric with the timestamp guarantee: partial failure leaves cache and
        // anchor stale so the next refresh retries instead of skipping as "fresh".
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any(), any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { manager.addGeofences(any()) } returns Result.failure(RuntimeException("boom"))

        repository.refresh(latitude = 0.0, longitude = 0.0)

        verify(exactly = 0) { store.saveCachedRegions(any()) }
        verify(exactly = 0) { store.saveCachedConfig(any()) }
        verify(exactly = 0) { store.saveLastApiFetchLocation(any()) }
    }

    @Test
    fun refresh_givenPreviouslyRegisteredIdsAbsentFromNew_expectStaleRemovedAfterAdd() = runTest {
        // OS-side geofence accumulation guard: previously-registered IDs not in the new
        // set must be removed, otherwise stale entries linger in the OS until the
        // per-app limit is hit. Ordering: add runs FIRST so a transient add failure
        // doesn't wipe the last-known-good state (see _expectStaleRemovalNotAttempted).
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns setOf(
            GeofenceConstants.MOVEMENT_TRIGGER_ID,
            "biz-old-1",
            "biz-old-2",
            "biz-shared"
        )
        val newBusiness = listOf(
            GeofenceRegion("biz-shared", 0.0, 0.0, 100f),
            GeofenceRegion("biz-new", 0.0, 0.0, 100f)
        )
        coEvery { apiService.fetchGeofences(any(), any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 5))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns newBusiness
        coEvery { manager.addGeofences(any()) } returns Result.success(Unit)
        coEvery { manager.removeGeofencesByIds(any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        // biz-old-1 and biz-old-2 are no longer in the new set; movement trigger and
        // biz-shared survive (same IDs, will be replaced by addGeofences).
        val staleSlot = slot<List<String>>()
        coVerifyOrder {
            manager.addGeofences(any())
            manager.removeGeofencesByIds(capture(staleSlot))
        }
        staleSlot.captured shouldContainSame listOf("biz-old-1", "biz-old-2")
    }

    @Test
    fun refresh_givenNoPreviousRegistration_expectNoRemoveCall() = runTest {
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any(), any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { manager.addGeofences(any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 0) { manager.removeGeofencesByIds(any()) }
    }

    @Test
    fun refresh_givenAddSucceedsButStaleRemovalFails_expectUnremovedStalePreserved() = runTest {
        // Order: add succeeds, then stale removal fails. The new batch is registered
        // and we treat the sync as successful — but we must preserve the unremoved
        // stale entries in the persisted set so the next refresh's diff sees them and
        // retries the cleanup. Without this, failed-stale geofences orphan in the OS.
        val newRegion = GeofenceRegion("biz-new", 0.0, 0.0, 100f)
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns setOf("biz-old")
        coEvery { apiService.fetchGeofences(any(), any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns listOf(newRegion)
        coEvery { manager.addGeofences(any()) } returns Result.success(Unit)
        coEvery { manager.removeGeofencesByIds(any()) } returns
            Result.failure(RuntimeException("remove boom"))
        val persisted = slot<Set<String>>()
        every { store.saveRegisteredIds(capture(persisted)) } returns Unit

        val result = repository.refresh(latitude = 0.0, longitude = 0.0)

        result.isSuccess shouldBeEqualTo true
        coVerifyOrder {
            manager.addGeofences(any())
            manager.removeGeofencesByIds(any())
        }
        verify { logger.logSyncSucceeded(1) }
        // Persisted set includes the unremoved stale ID — next refresh will retry it.
        persisted.captured shouldContainSame
            setOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "biz-new", "biz-old")
    }

    @Test
    fun refresh_givenAllPreviousAbsentFromNew_expectAllRemovedAndEmptyRegistered() = runTest {
        // Account transitioned from "has geofences" to "no geofences": every previously
        // registered ID must be removed, including the movement trigger.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns setOf(
            GeofenceConstants.MOVEMENT_TRIGGER_ID,
            "biz-old"
        )
        coEvery { apiService.fetchGeofences(any(), any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.addGeofences(any()) } returns Result.success(Unit)
        coEvery { manager.removeGeofencesByIds(any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        val staleSlot = slot<List<String>>()
        coVerify { manager.removeGeofencesByIds(capture(staleSlot)) }
        staleSlot.captured shouldContainSame listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "biz-old")
    }

    @Test
    fun refresh_givenZeroBusinessGeofences_expectNothingRegisteredIncludingMovementTrigger() = runTest {
        // Customers without configured geofences must pay zero runtime cost:
        // no movement trigger registered, no OS-side geofence activity.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any(), any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns emptyList()
        val captured = slot<List<GeofenceRegion>>()
        coEvery { manager.addGeofences(capture(captured)) } returns Result.success(Unit)

        val result = repository.refresh(latitude = 12.34, longitude = 56.78)

        result.isSuccess shouldBeEqualTo true
        captured.captured.shouldBeEmpty()
        verify { logger.logSyncSucceeded(0) }
    }

    @Test
    fun refresh_givenManagerAddFails_expectStaleRemovalNotAttemptedAndPreviousStatePreserved() = runTest {
        // Critical 7g invariant: when addGeofences fails, removeGeofencesByIds must
        // NOT be called. Otherwise we'd destroy the last-known-good OS registrations
        // and leave the device with NO geofences at all until the next refresh.
        // Store and timestamp also stay untouched so the next refresh sees the same
        // previous state and the freshness check retries instead of skipping.
        val error = RuntimeException("gms boom")
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns setOf("biz-old")
        coEvery { apiService.fetchGeofences(any(), any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 5))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-new", 0.0, 0.0, 100f))
        coEvery { manager.addGeofences(any()) } returns Result.failure(error)

        val result = repository.refresh(latitude = 0.0, longitude = 0.0)

        result.isFailure shouldBeEqualTo true
        result.exceptionOrNull() shouldBeEqualTo error
        coVerify(exactly = 0) { manager.removeGeofencesByIds(any()) }
        verify(exactly = 0) { store.saveRegisteredIds(any()) }
        verify(exactly = 0) { store.setLastSyncTimestamp(any()) }
        verify(exactly = 0) { logger.logSyncSucceeded(any()) }
    }

    @Test
    fun refresh_givenSecondCallWhileFirstInFlight_expectSecondDroppedAndOnlyOneApiCall() = runTest {
        // In-flight gate dedups concurrent triggers: the second refresh returns
        // success immediately without firing a redundant API call or attempting
        // a second OS registration.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any(), any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns emptyList()

        val addGeofencesActive = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)
        coEvery { manager.addGeofences(any()) } coAnswers {
            val n = addGeofencesActive.incrementAndGet()
            maxObservedConcurrency.updateAndGet { current -> maxOf(current, n) }
            delay(50)
            addGeofencesActive.decrementAndGet()
            Result.success(Unit)
        }

        coroutineScope {
            launch { repository.refresh(latitude = 1.0, longitude = 1.0) }
            launch { repository.refresh(latitude = 2.0, longitude = 2.0) }
        }

        maxObservedConcurrency.get() shouldBeEqualTo 1
        coVerify(exactly = 1) { apiService.fetchGeofences(any(), any(), any()) }
        verify { logger.logSyncSkipped(match { it.contains("refresh already in progress") }) }
    }

    @Test
    fun refresh_givenInFlightGateCleared_expectSubsequentCallProceeds() = runTest {
        // Pins the contract that the in-flight gate is released even on failure,
        // so a follow-up refresh isn't permanently locked out.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any(), any(), any()) } returnsMany listOf(
            Result.failure(IOException("first call fails")),
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        )
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.addGeofences(any()) } returns Result.success(Unit)

        val first = repository.refresh(latitude = 0.0, longitude = 0.0)
        val second = repository.refresh(latitude = 0.0, longitude = 0.0)

        first.isFailure shouldBeEqualTo true
        second.isSuccess shouldBeEqualTo true
        coVerify(exactly = 2) { apiService.fetchGeofences(any(), any(), any()) }
    }

    @Test
    fun refresh_givenUserChangesDuringApiCall_expectNoWriteToStoreOrManager() = runTest {
        // Defends against a reset()/sign-out racing with an in-flight refresh:
        // the userId recheck inside the state lock prevents writing the previous
        // user's geofences after a sign-out cleared state.
        every { secureUserStore.getUserId() } returnsMany listOf("user-A", "user-B")
        coEvery { apiService.fetchGeofences("user-A", any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))

        val result = repository.refresh(latitude = 0.0, longitude = 0.0)

        result.isSuccess shouldBeEqualTo true
        verify(exactly = 0) { store.saveRegisteredIds(any()) }
        verify(exactly = 0) { store.setLastSyncTimestamp(any()) }
        coVerify(exactly = 0) { manager.addGeofences(any()) }
        verify { logger.logSyncSkipped(match { it.contains("user changed") }) }
    }

    @Test
    fun refresh_givenUserSignsOutDuringApiCall_expectNoWriteToStoreOrManager() = runTest {
        every { secureUserStore.getUserId() } returnsMany listOf("user-A", null)
        coEvery { apiService.fetchGeofences("user-A", any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))

        val result = repository.refresh(latitude = 0.0, longitude = 0.0)

        result.isSuccess shouldBeEqualTo true
        verify(exactly = 0) { store.saveRegisteredIds(any()) }
        coVerify(exactly = 0) { manager.addGeofences(any()) }
        verify { logger.logSyncSkipped(match { it.contains("user changed") }) }
    }

    @Test
    fun reset_givenManagerSucceeds_expectStoreAndManagerCleared() = runTest {
        // Sign-out cleanup: the persisted set must be wiped AND OS-side state
        // (registered geofences + receiver) cleared via manager.clearAll so a
        // subsequent user doesn't inherit anything from the previous one.
        coEvery { manager.clearAll() } returns Result.success(Unit)

        val result = repository.reset()

        result.isSuccess shouldBeEqualTo true
        coVerifyOrder {
            manager.clearAll()
            store.clearAll()
        }
    }

    @Test
    fun reset_givenNewUserSignedInDuringWait_expectSkipWipe() = runTest {
        // Sign-out + immediate-sign-in race — see reset() for the rationale.
        every { secureUserStore.getUserId() } returns "user-B"

        val result = repository.reset()

        result.isSuccess shouldBeEqualTo true
        coVerify(exactly = 0) { manager.clearAll() }
        verify(exactly = 0) { store.clearAll() }
        verify { logger.logSyncSkipped(match { it.contains("reset superseded") }) }
    }

    @Test
    fun reset_givenManagerFails_expectStorePreservedForSelfHeal() = runTest {
        // If manager.clearAll fails (transient GMS error), the store MUST be
        // preserved — otherwise OS-side registrations orphan with no record to
        // drive cleanup. The next refresh's stale-cleanup diff uses the store
        // to retry the removal.
        val error = RuntimeException("gms clear boom")
        coEvery { manager.clearAll() } returns Result.failure(error)

        val result = repository.reset()

        result.isFailure shouldBeEqualTo true
        result.exceptionOrNull() shouldBeEqualTo error
        verify(exactly = 0) { store.clearAll() }
    }

    // ---------- handleMovement / tier dispatch ----------

    @Test
    fun handleMovement_givenNoUserId_expectSkipAndNoOp() = runTest {
        every { secureUserStore.getUserId() } returns null

        val result = repository.handleMovement(latitude = 0.0, longitude = 0.0)

        result.isSuccess shouldBeEqualTo true
        coVerify(exactly = 0) { apiService.fetchGeofences(any(), any(), any()) }
        coVerify(exactly = 0) { manager.addGeofences(any()) }
    }

    @Test
    fun handleMovement_givenNoAnchor_expectRemoteFetch() = runTest {
        // First EXIT after install / sign-out / clearAll: no anchor yet, so we
        // can't compute the tier-A distance. Fall through to a remote fetch.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastApiFetchLocation() } returns null
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any(), any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.addGeofences(any()) } returns Result.success(Unit)

        repository.handleMovement(latitude = 1.0, longitude = 2.0)

        coVerify { apiService.fetchGeofences("user-42", 1.0, 2.0) }
    }

    @Test
    fun handleMovement_givenNoCachedConfig_expectRemoteFetch() = runTest {
        // Defensive: anchor without cached config shouldn't happen in practice
        // (both are written together), but if it does, fall back to remote fetch
        // rather than guessing thresholds.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getCachedConfig() } returns null
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any(), any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.addGeofences(any()) } returns Result.success(Unit)

        repository.handleMovement(latitude = 0.0, longitude = 0.0)

        coVerify { apiService.fetchGeofences(any(), any(), any()) }
    }

    @Test
    fun handleMovement_givenAnchorWithinThreshold_expectLocalRerankAndNoApiCall() = runTest {
        // Anchor at (0, 0), current location ~111m away (0, 0.001), threshold 5km
        // → tier A: re-rank cache, register, no API.
        val cached = listOf(
            GeofenceRegion("biz-1", 0.0, 0.0, 100f),
            GeofenceRegion("biz-2", 0.0, 1.0, 100f)
        )
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns emptySet()
        every { distanceFilter.nearest(cached, any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { manager.addGeofences(any()) } returns Result.success(Unit)

        repository.handleMovement(latitude = 0.0, longitude = 0.001)

        coVerify(exactly = 0) { apiService.fetchGeofences(any(), any(), any()) }
        coVerify { manager.addGeofences(any()) }
        verify { distanceFilter.nearest(cached, 0.0, 0.001, any()) }
    }

    @Test
    fun handleMovement_givenAnchorBeyondThreshold_expectRemoteFetch() = runTest {
        // Anchor at (0, 0), current location ~11km away (0, 0.1), threshold 5km
        // → tier B: API fetch.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any(), any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.addGeofences(any()) } returns Result.success(Unit)

        repository.handleMovement(latitude = 0.0, longitude = 0.1)

        coVerify { apiService.fetchGeofences("user-42", 0.0, 0.1) }
    }

    @Test
    fun handleMovement_givenAnchorBeyondThreshold_expectCacheAndAnchorPersisted() = runTest {
        // Tier B via handleMovement must wire through to the same persist path
        // refresh() uses — new anchor is the current location, cache + config + timestamp written.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any(), any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { manager.addGeofences(any()) } returns Result.success(Unit)
        val anchorSlot = slot<GeofenceLocation>()
        every { store.saveLastApiFetchLocation(capture(anchorSlot)) } returns Unit

        repository.handleMovement(latitude = 0.0, longitude = 0.1)

        verify { store.saveCachedRegions(any()) }
        verify { store.saveCachedConfig(any()) }
        verify { store.setLastSyncTimestamp(any()) }
        anchorSlot.captured shouldBeEqualTo GeofenceLocation(0.0, 0.1)
    }

    @Test
    fun handleMovement_givenLocalRerankSucceeds_expectCacheAndAnchorAndTimestampNotUpdated() = runTest {
        // Tier A reuses the existing anchor so the 5km threshold keeps measuring from the same point.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { store.getRegisteredIds() } returns emptySet()
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { manager.addGeofences(any()) } returns Result.success(Unit)

        repository.handleMovement(latitude = 0.0, longitude = 0.001)

        verify(exactly = 0) { store.saveCachedRegions(any()) }
        verify(exactly = 0) { store.saveCachedConfig(any()) }
        verify(exactly = 0) { store.saveLastApiFetchLocation(any()) }
        verify(exactly = 0) { store.setLastSyncTimestamp(any()) }
    }

    @Test
    fun handleMovement_givenSecondCallWhileFirstInFlight_expectSecondDropped() = runTest {
        // handleMovement and refresh share the same in-flight gate. A movement
        // EXIT arriving while a refresh is mid-API call must drop fast — same
        // dedup guarantee.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastApiFetchLocation() } returns null
        every { store.getCachedConfig() } returns null
        every { store.getRegisteredIds() } returns emptySet()
        val concurrentApiCalls = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)
        coEvery { apiService.fetchGeofences(any(), any(), any()) } coAnswers {
            val n = concurrentApiCalls.incrementAndGet()
            maxObservedConcurrency.updateAndGet { current -> maxOf(current, n) }
            delay(50)
            concurrentApiCalls.decrementAndGet()
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        }
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.addGeofences(any()) } returns Result.success(Unit)

        coroutineScope {
            launch { repository.handleMovement(latitude = 1.0, longitude = 1.0) }
            launch { repository.handleMovement(latitude = 2.0, longitude = 2.0) }
        }

        maxObservedConcurrency.get() shouldBeEqualTo 1
        coVerify(exactly = 1) { apiService.fetchGeofences(any(), any(), any()) }
    }

    // ---------- restoreFromCache (boot path) ----------

    @Test
    fun restoreFromCache_givenNoUserId_expectSkipAndNoRegistration() = runTest {
        every { secureUserStore.getUserId() } returns null

        val result = repository.restoreFromCache()

        result.isSuccess shouldBeEqualTo true
        coVerify(exactly = 0) { manager.addGeofences(any()) }
    }

    @Test
    fun restoreFromCache_givenNoAnchor_expectSkipAndNoRegistration() = runTest {
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastApiFetchLocation() } returns null
        every { store.getCachedConfig() } returns sampleConfig()

        val result = repository.restoreFromCache()

        result.isSuccess shouldBeEqualTo true
        coVerify(exactly = 0) { manager.addGeofences(any()) }
    }

    @Test
    fun restoreFromCache_givenNoCachedConfig_expectSkipAndNoRegistration() = runTest {
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(1.0, 2.0)
        every { store.getCachedConfig() } returns null

        val result = repository.restoreFromCache()

        result.isSuccess shouldBeEqualTo true
        coVerify(exactly = 0) { manager.addGeofences(any()) }
    }

    @Test
    fun restoreFromCache_givenCachedState_expectRegistrationAtAnchorLocation() = runTest {
        // Anchor is the effective "current location" on boot — no real-time location available.
        val anchor = GeofenceLocation(latitude = 12.34, longitude = 56.78)
        val cached = listOf(
            GeofenceRegion("biz-1", 12.34, 56.78, 100f),
            GeofenceRegion("biz-2", 13.0, 57.0, 100f)
        )
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastApiFetchLocation() } returns anchor
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns emptySet()
        every { distanceFilter.nearest(cached, 12.34, 56.78, any()) } returns
            listOf(GeofenceRegion("biz-1", 12.34, 56.78, 100f))
        coEvery { manager.addGeofences(any()) } returns Result.success(Unit)

        repository.restoreFromCache()

        verify { distanceFilter.nearest(cached, 12.34, 56.78, any()) }
        coVerify { manager.addGeofences(any()) }
        // No API call — restore reuses the existing cache.
        coVerify(exactly = 0) { apiService.fetchGeofences(any(), any(), any()) }
        // Anchor + timestamp stay intact; restore doesn't re-fetch.
        verify(exactly = 0) { store.saveLastApiFetchLocation(any()) }
        verify(exactly = 0) { store.setLastSyncTimestamp(any()) }
    }

    private fun sampleConfig(): GeofenceConfig = GeofenceConfig(
        localRefreshTriggerRadius = 1_000f,
        remoteFetchRefreshTriggerRadius = 5_000f,
        remoteFetchRefreshExpiryMs = 86_400_000L,
        duplicateEventsExpiryMs = 3_600_000L,
        maxBusinessGeofences = 19
    )

    private fun sampleResponse(
        maxBusinessGeofences: Int,
        localRefreshTriggerRadius: Float = 1000f
    ): GeofenceApiResponse {
        val json = """
            {
              "config": {
                "local_refresh_trigger_radius": $localRefreshTriggerRadius,
                "remote_fetch_refresh_trigger_radius": 5000,
                "remote_fetch_refresh_expiry_time": 86400,
                "duplicate_events_expiry_time": 3600,
                "android": { "max_business_geofence": $maxBusinessGeofences }
              },
              "geofences": [
                { "id": "g-1", "name": "n1", "latitude": 0.0, "longitude": 0.0, "radius": 100, "transition_types": ["enter"], "last_updated": 1 }
              ]
            }
        """.trimIndent()
        return jsonSerializer.decode(GeofenceApiResponse.serializer(), json)
    }
}
