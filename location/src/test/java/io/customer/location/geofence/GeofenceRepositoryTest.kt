package io.customer.location.geofence

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.location.geofence.api.GeofenceApiResponse
import io.customer.location.geofence.api.GeofenceApiService
import io.customer.location.geofence.store.GeofenceRegionStore
import io.customer.sdk.core.util.Clock
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
    private val clock: Clock = mockk(relaxed = true)
    private val logger: GeofenceLogger = mockk(relaxed = true)
    private val jsonSerializer = GeofenceJsonSerializer()

    private lateinit var repository: GeofenceRepositoryImpl

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { })
        // Default: mirror real time so tests using relative timestamps work
        // without churn. Override for deterministic timing.
        every { clock.currentTimeMillis() } answers { System.currentTimeMillis() }
        repository = GeofenceRepositoryImpl(
            apiService = apiService,
            store = store,
            distanceFilter = distanceFilter,
            manager = manager,
            secureUserStore = secureUserStore,
            clock = clock,
            logger = logger
        )
    }

    @Test
    fun refresh_givenRecentSuccessfulSyncAndNotForced_expectSkipApiCall() = runTest {
        // Repeated identify within the freshness window must not hit the API.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L // 1 min ago
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)

        val result = repository.refresh(latitude = 0.0, longitude = 0.0)

        result.isSuccess shouldBeEqualTo true
        coVerify(exactly = 0) { apiService.fetchGeofences(any(), any()) }
        verify { logger.logSyncSkippedFresh() }
    }

    @Test
    fun refresh_givenFreshCacheButOsRegsWiped_expectLocalRefreshFromCache() = runTest {
        // Sign-out → sign-in within the freshness window: workspace cache
        // survived but OS regs + registeredIds were wiped. Must re-register
        // from cache instead of skipping, otherwise the new user has no OS
        // geofences until the next stale-window expiry.
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns emptySet()
        every { store.getCachedConfig() } returns sampleConfig()
        every { distanceFilter.nearest(cached, any(), any(), any()) } returns cached
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 0) { apiService.fetchGeofences(any(), any()) }
        coVerify { manager.replaceGeofences(any(), any()) }
    }

    @Test
    fun refresh_givenFreshCacheAndOsRegsWipedButNullCachedConfig_expectLocalRefreshWithFallback() = runTest {
        // Backend may not ship `config` yet — null cachedConfig must NOT skip
        // re-registration, otherwise the new user has no geofences until the
        // stale window expires. Falls back to default thresholds.
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns emptySet()
        every { store.getCachedConfig() } returns null
        every { distanceFilter.nearest(cached, any(), any(), any()) } returns cached
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 0) { apiService.fetchGeofences(any(), any()) }
        coVerify { manager.replaceGeofences(any(), any()) }
    }

    @Test
    fun refresh_givenFreshButMovedFarFromAnchor_expectApiCalled() = runTest {
        // App killed → user travels far → reopens within freshness window.
        // Time-based check alone would skip and leave geofences stale at the
        // old location; the distance check forces a fresh fetch.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getRegisteredIds() } returns setOf("biz-1")
        coEvery { apiService.fetchGeofences(any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        // 1° latitude ≈ 111 km, way beyond the 5 km remoteFetchRefreshTriggerRadius.
        repository.refresh(latitude = 1.0, longitude = 0.0)

        coVerify { apiService.fetchGeofences(1.0, 0.0) }
        verify(exactly = 0) { logger.logSyncSkippedFresh() }
    }

    @Test
    fun refresh_givenStaleLastSync_expectApiCalled() = runTest {
        // Last sync older than threshold => proceed with refresh.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns
            System.currentTimeMillis() - GeofenceConstants.STALE_THRESHOLD_MS - 1_000L
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify { apiService.fetchGeofences(any(), any()) }
    }

    @Test
    fun refresh_givenCachedConfigWithLongerExpiry_expectSkipWhenWithinIt() = runTest {
        // Cached config's `remoteFetchRefreshExpiry` overrides the constant.
        // With expiry=72h and lastSync=25h ago we skip, whereas the 24h
        // constant alone would have triggered a fresh API call.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns
            System.currentTimeMillis() - (25 * 60 * 60 * 1_000L)
        every { store.getCachedConfig() } returns
            sampleConfig(remoteFetchRefreshExpiry = 72 * 60 * 60 * 1_000L)

        val result = repository.refresh(latitude = 0.0, longitude = 0.0)

        result.isSuccess shouldBeEqualTo true
        coVerify(exactly = 0) { apiService.fetchGeofences(any(), any()) }
        verify { logger.logSyncSkippedFresh() }
    }

    @Test
    fun refresh_givenCachedConfigWithShorterExpiry_expectApiCallSooner() = runTest {
        // Symmetric case: shorter server window (1h) trips earlier than the
        // 24h constant. A sync 2h ago now triggers an API call.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns
            System.currentTimeMillis() - (2 * 60 * 60 * 1_000L)
        every { store.getCachedConfig() } returns
            sampleConfig(remoteFetchRefreshExpiry = 60 * 60 * 1_000L)
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify { apiService.fetchGeofences(any(), any()) }
    }

    @Test
    fun refresh_givenNoPreviousSync_expectApiCalled() = runTest {
        // First-ever sync: no timestamp, threshold check is a no-op, proceed.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns null
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify { apiService.fetchGeofences(any(), any()) }
    }

    @Test
    fun refresh_givenNoUserId_expectSkipAndSuccessAndNoApiCall() = runTest {
        every { secureUserStore.getUserId() } returns null

        val result = repository.refresh(latitude = 12.34, longitude = 56.78)

        result.isSuccess shouldBeEqualTo true
        coVerify(exactly = 0) { apiService.fetchGeofences(any(), any()) }
        coVerify(exactly = 0) { manager.replaceGeofences(any(), any()) }
        verify(exactly = 0) { store.saveRegisteredIds(any()) }
        verify { logger.logSyncSkipped(match { it.contains("no identified user") }) }
    }

    @Test
    fun refresh_givenBlankUserId_expectSkipAndSuccess() = runTest {
        every { secureUserStore.getUserId() } returns "   "

        val result = repository.refresh(latitude = 0.0, longitude = 0.0)

        result.isSuccess shouldBeEqualTo true
        coVerify(exactly = 0) { apiService.fetchGeofences(any(), any()) }
    }

    @Test
    fun refresh_givenApiFailure_expectFailurePropagatedAndNoPersistOrRegister() = runTest {
        val error = IOException("network down")
        every { secureUserStore.getUserId() } returns "user-42"
        coEvery { apiService.fetchGeofences(any(), any()) } returns Result.failure(error)

        val result = repository.refresh(latitude = 12.34, longitude = 56.78)

        result.isFailure shouldBeEqualTo true
        result.exceptionOrNull() shouldBeEqualTo error
        verify { logger.logSyncFailed(match { it?.contains("network down") == true }) }
        verify(exactly = 0) { store.saveRegisteredIds(any()) }
        verify(exactly = 0) { store.setLastSyncTimestamp(any()) }
        coVerify(exactly = 0) { manager.replaceGeofences(any(), any()) }
    }

    @Test
    fun refresh_givenSuccessWithBusiness_expectMovementTriggerLocationPersisted() = runTest {
        // Persisted point is the user's lat/lng at registration time — boot
        // restore reads it later as the effective coordinates.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)
        val capturedLoc = slot<GeofenceLocation>()
        every { store.saveLastMovementTriggerLocation(capture(capturedLoc)) } returns Unit

        repository.refresh(latitude = 12.34, longitude = 56.78)

        capturedLoc.captured shouldBeEqualTo GeofenceLocation(latitude = 12.34, longitude = 56.78)
    }

    @Test
    fun refresh_givenEmptyBusinessSet_expectMovementTriggerLocationCleared() = runTest {
        // Account transitioned to 0 businesses — no movement trigger exists,
        // so any previously-stored location is stale and must be cleared.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 12.34, longitude = 56.78)

        verify(exactly = 0) { store.saveLastMovementTriggerLocation(any()) }
        verify { store.clearLastMovementTriggerLocation() }
    }

    @Test
    fun refresh_givenManagerAddFails_expectMovementTriggerLocationNotPersisted() = runTest {
        // Persistence is gated on add success — a failed registration must leave the
        // last-known good movement location intact (next refresh retries).
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.failure(RuntimeException("boom"))

        repository.refresh(latitude = 12.34, longitude = 56.78)

        verify(exactly = 0) { store.saveLastMovementTriggerLocation(any()) }
    }

    @Test
    fun refresh_givenBusinessGeofences_expectMovementTriggerPrependedAndRegistered() = runTest {
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        val filtered = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { apiService.fetchGeofences(12.34, 56.78) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3, localRefreshTriggerRadius = 1500f))
        every { distanceFilter.nearest(any(), 12.34, 56.78, 3) } returns filtered
        val captured = slot<List<GeofenceRegion>>()
        coEvery { manager.replaceGeofences(capture(captured), any()) } returns Result.success(Unit)

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
        coEvery { apiService.fetchGeofences(12.34, 56.78) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3, localRefreshTriggerRadius = 1500f))
        every { distanceFilter.nearest(any(), 12.34, 56.78, 3) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)
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
        coEvery { apiService.fetchGeofences(any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.failure(RuntimeException("boom"))

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
        coEvery { apiService.fetchGeofences(any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 5))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns newBusiness
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)
        coEvery { manager.removeGeofencesByIds(any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        // biz-old-1 and biz-old-2 are no longer in the new set; movement trigger and
        // biz-shared survive (same IDs, will be replaced by replaceGeofences).
        val staleSlot = slot<List<String>>()
        coVerifyOrder {
            manager.replaceGeofences(any(), any())
            manager.removeGeofencesByIds(capture(staleSlot))
        }
        staleSlot.captured shouldContainSame listOf("biz-old-1", "biz-old-2")
    }

    @Test
    fun refresh_givenNoPreviousRegistration_expectNoRemoveCall() = runTest {
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

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
        coEvery { apiService.fetchGeofences(any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns listOf(newRegion)
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)
        coEvery { manager.removeGeofencesByIds(any()) } returns
            Result.failure(RuntimeException("remove boom"))
        val persisted = slot<Set<String>>()
        every { store.saveRegisteredIds(capture(persisted)) } returns Unit

        val result = repository.refresh(latitude = 0.0, longitude = 0.0)

        result.isSuccess shouldBeEqualTo true
        coVerifyOrder {
            manager.replaceGeofences(any(), any())
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
        coEvery { apiService.fetchGeofences(any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)
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
        coEvery { apiService.fetchGeofences(any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns emptyList()
        val captured = slot<List<GeofenceRegion>>()
        coEvery { manager.replaceGeofences(capture(captured), any()) } returns Result.success(Unit)

        val result = repository.refresh(latitude = 12.34, longitude = 56.78)

        result.isSuccess shouldBeEqualTo true
        captured.captured.shouldBeEmpty()
        verify { logger.logSyncSucceeded(0) }
    }

    @Test
    fun refresh_givenManagerAddFails_expectStaleRemovalNotAttemptedAndPreviousStatePreserved() = runTest {
        // Critical 7g invariant: when replaceGeofences fails, removeGeofencesByIds must
        // NOT be called. Otherwise we'd destroy the last-known-good OS registrations
        // and leave the device with NO geofences at all until the next refresh.
        // Store and timestamp also stay untouched so the next refresh sees the same
        // previous state and the freshness check retries instead of skipping.
        val error = RuntimeException("gms boom")
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns setOf("biz-old")
        coEvery { apiService.fetchGeofences(any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 5))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-new", 0.0, 0.0, 100f))
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.failure(error)

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
        coEvery { apiService.fetchGeofences(any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns emptyList()

        val addGeofencesActive = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)
        coEvery { manager.replaceGeofences(any(), any()) } coAnswers {
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
        coVerify(exactly = 1) { apiService.fetchGeofences(any(), any()) }
        verify { logger.logSyncSkipped(match { it.contains("refresh already in progress") }) }
    }

    @Test
    fun refresh_givenInFlightGateCleared_expectSubsequentCallProceeds() = runTest {
        // Pins the contract that the in-flight gate is released even on failure,
        // so a follow-up refresh isn't permanently locked out.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any(), any()) } returnsMany listOf(
            Result.failure(IOException("first call fails")),
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        )
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        val first = repository.refresh(latitude = 0.0, longitude = 0.0)
        val second = repository.refresh(latitude = 0.0, longitude = 0.0)

        first.isFailure shouldBeEqualTo true
        second.isSuccess shouldBeEqualTo true
        coVerify(exactly = 2) { apiService.fetchGeofences(any(), any()) }
    }

    @Test
    fun refresh_givenUserChangesDuringApiCall_expectNoWriteToStoreOrManager() = runTest {
        // Defends against a reset()/sign-out racing with an in-flight refresh:
        // the userId recheck inside the state lock prevents writing the previous
        // user's geofences after a sign-out cleared state.
        every { secureUserStore.getUserId() } returnsMany listOf("user-A", "user-B")
        coEvery { apiService.fetchGeofences(any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))

        val result = repository.refresh(latitude = 0.0, longitude = 0.0)

        result.isSuccess shouldBeEqualTo true
        verify(exactly = 0) { store.saveRegisteredIds(any()) }
        verify(exactly = 0) { store.setLastSyncTimestamp(any()) }
        coVerify(exactly = 0) { manager.replaceGeofences(any(), any()) }
        verify { logger.logSyncSkipped(match { it.contains("user changed") }) }
    }

    @Test
    fun refresh_givenUserSignsOutDuringApiCall_expectNoWriteToStoreOrManager() = runTest {
        every { secureUserStore.getUserId() } returnsMany listOf("user-A", null)
        coEvery { apiService.fetchGeofences(any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))

        val result = repository.refresh(latitude = 0.0, longitude = 0.0)

        result.isSuccess shouldBeEqualTo true
        verify(exactly = 0) { store.saveRegisteredIds(any()) }
        coVerify(exactly = 0) { manager.replaceGeofences(any(), any()) }
        verify { logger.logSyncSkipped(match { it.contains("user changed") }) }
    }

    @Test
    fun refresh_givenRemoteFetchAndCachedMatchesIncoming_expectIdForwardedAsExisting() = runTest {
        // Tier B remote fetch where the cached region equals the incoming one — no
        // backend-side edit. The diff helper forwards the overlap ID so the manager
        // can skip the re-upsert that would otherwise trigger GMS state
        // reconciliation and spurious EXITs.
        val region = GeofenceRegion("biz-1", 1.0, 2.0, 100f)
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns null
        every { store.getRegisteredIds() } returns setOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "biz-1")
        every { store.getCachedRegions() } returns listOf(region)
        coEvery { apiService.fetchGeofences(any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns listOf(region)
        val existingSlot = slot<Set<String>>()
        coEvery { manager.replaceGeofences(any(), capture(existingSlot)) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        existingSlot.captured shouldContainSame setOf("biz-1")
    }

    @Test
    fun refresh_givenRemoteFetchAndCachedParamsDifferFromIncoming_expectIdExcludedFromExisting() = runTest {
        // Backend edited biz-1's radius (100m → 200m). Cache still holds the old
        // value at diff time; equality fails on the mismatch so biz-1 falls out of
        // existingBusinessIds and gets re-registered. Without this, GMS would keep
        // the 100m geofence after the cache moved to 200m.
        val cached = GeofenceRegion("biz-1", 1.0, 2.0, 100f)
        val incoming = cached.copy(radius = 200f)
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns null
        every { store.getRegisteredIds() } returns setOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "biz-1")
        every { store.getCachedRegions() } returns listOf(cached)
        coEvery { apiService.fetchGeofences(any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns listOf(incoming)
        val existingSlot = slot<Set<String>>()
        coEvery { manager.replaceGeofences(any(), capture(existingSlot)) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        existingSlot.captured.shouldBeEmpty()
    }

    @Test
    fun reset_givenManagerSucceeds_expectUserScopedStateClearedAndWorkspaceCachePreserved() = runTest {
        // Sign-out cleanup: drop OS registrations + wipe user-specific state
        // (anchor, movement-trigger location, registered IDs). Workspace
        // cache (regions, config, last-sync) survives so a quick re-login
        // skips a redundant API hit.
        every { secureUserStore.getUserId() } returns null
        coEvery { manager.clearAll() } returns Result.success(Unit)

        val result = repository.reset(signedOutUserId = "user-A")

        result.isSuccess shouldBeEqualTo true
        coVerifyOrder {
            manager.clearAll()
            store.clearUserScopedState()
        }
        verify(exactly = 0) { store.clearAll() }
    }

    @Test
    fun reset_givenNewUserSignedInDuringWait_expectSkipWipe() = runTest {
        // True re-login race: User A signed out, User B signed in before our
        // reset ran. Current user differs from the one being reset → skip wipe
        // so we don't clobber User B's freshly-written state.
        every { secureUserStore.getUserId() } returns "user-B"

        val result = repository.reset(signedOutUserId = "user-A")

        result.isSuccess shouldBeEqualTo true
        coVerify(exactly = 0) { manager.clearAll() }
        verify(exactly = 0) { store.clearUserScopedState() }
        verify(exactly = 0) { store.clearAll() }
        verify { logger.logSyncSkipped(match { it.contains("reset superseded") }) }
    }

    @Test
    fun reset_givenSecureUserStoreNotYetCleared_expectWipeProceeds() = runTest {
        // The two ResetEvent subscribers (datapipelines clearing
        // secureUserStore, location running reset) race. If our reset runs
        // first, secureUserStore.getUserId() still returns the signed-out
        // user — but it's the SAME id, not a different user signed in. Must
        // proceed with wipe; this is the regression Cursor flagged on #706.
        every { secureUserStore.getUserId() } returns "user-A"
        coEvery { manager.clearAll() } returns Result.success(Unit)

        val result = repository.reset(signedOutUserId = "user-A")

        result.isSuccess shouldBeEqualTo true
        coVerifyOrder {
            manager.clearAll()
            store.clearUserScopedState()
        }
    }

    @Test
    fun reset_givenManagerFails_expectStorePreservedForSelfHeal() = runTest {
        // If manager.clearAll fails (transient GMS error), the store MUST be
        // preserved — otherwise OS-side registrations orphan with no record to
        // drive cleanup. The next refresh's stale-cleanup diff uses the store
        // to retry the removal.
        every { secureUserStore.getUserId() } returns null
        val error = RuntimeException("gms clear boom")
        coEvery { manager.clearAll() } returns Result.failure(error)

        val result = repository.reset(signedOutUserId = "user-A")

        result.isFailure shouldBeEqualTo true
        result.exceptionOrNull() shouldBeEqualTo error
        verify(exactly = 0) { store.clearUserScopedState() }
        verify(exactly = 0) { store.clearAll() }
    }

    // ---------- handleMovement / tier dispatch ----------

    @Test
    fun handleMovement_givenNoUserId_expectSkipAndNoOp() = runTest {
        every { secureUserStore.getUserId() } returns null

        val result = repository.handleMovement(latitude = 0.0, longitude = 0.0)

        result.isSuccess shouldBeEqualTo true
        coVerify(exactly = 0) { apiService.fetchGeofences(any(), any()) }
        coVerify(exactly = 0) { manager.replaceGeofences(any(), any()) }
    }

    @Test
    fun handleMovement_givenNoAnchor_expectRemoteFetch() = runTest {
        // First EXIT after install / sign-out / clearAll: no anchor yet, so we
        // can't compute the tier-A distance. Fall through to a remote fetch.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastApiFetchLocation() } returns null
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.handleMovement(latitude = 1.0, longitude = 2.0)

        coVerify { apiService.fetchGeofences(1.0, 2.0) }
    }

    @Test
    fun handleMovement_givenNoCachedConfig_expectTierAUsingFallbackThreshold() = runTest {
        // Null cached config falls back to defaults — anchor within the
        // fallback 5km radius still routes to Tier A (local re-rank), not B.
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getCachedConfig() } returns null
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns emptySet()
        every { distanceFilter.nearest(cached, any(), any(), any()) } returns cached
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        // ~111m from anchor — well within the fallback 5km threshold.
        repository.handleMovement(latitude = 0.0, longitude = 0.001)

        coVerify(exactly = 0) { apiService.fetchGeofences(any(), any()) }
        coVerify { manager.replaceGeofences(any(), any()) }
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
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.handleMovement(latitude = 0.0, longitude = 0.001)

        coVerify(exactly = 0) { apiService.fetchGeofences(any(), any()) }
        coVerify { manager.replaceGeofences(any(), any()) }
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
        coEvery { apiService.fetchGeofences(any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.handleMovement(latitude = 0.0, longitude = 0.1)

        coVerify { apiService.fetchGeofences(0.0, 0.1) }
    }

    @Test
    fun handleMovement_givenAnchorBeyondThreshold_expectCacheAndAnchorPersisted() = runTest {
        // Tier B via handleMovement must wire through to the same persist path
        // refresh() uses — new anchor is the current location, cache + config + timestamp written.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)
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
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

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
        coEvery { apiService.fetchGeofences(any(), any()) } coAnswers {
            val n = concurrentApiCalls.incrementAndGet()
            maxObservedConcurrency.updateAndGet { current -> maxOf(current, n) }
            delay(50)
            concurrentApiCalls.decrementAndGet()
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        }
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        coroutineScope {
            launch { repository.handleMovement(latitude = 1.0, longitude = 1.0) }
            launch { repository.handleMovement(latitude = 2.0, longitude = 2.0) }
        }

        maxObservedConcurrency.get() shouldBeEqualTo 1
        coVerify(exactly = 1) { apiService.fetchGeofences(any(), any()) }
    }

    // ---------- restoreFromCache (boot path) ----------

    @Test
    fun restoreFromCache_givenNoUserId_expectSkipAndNoRegistration() = runTest {
        every { secureUserStore.getUserId() } returns null

        val result = repository.restoreFromCache()

        result.isSuccess shouldBeEqualTo true
        coVerify(exactly = 0) { manager.replaceGeofencesForBootRestore(any()) }
    }

    @Test
    fun restoreFromCache_givenNoMovementLocationAndNoAnchor_expectSkip() = runTest {
        // Neither location available — nothing to restore from.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastMovementTriggerLocation() } returns null
        every { store.getLastApiFetchLocation() } returns null
        every { store.getCachedConfig() } returns sampleConfig()

        val result = repository.restoreFromCache()

        result.isSuccess shouldBeEqualTo true
        coVerify(exactly = 0) { manager.replaceGeofencesForBootRestore(any()) }
    }

    @Test
    fun restoreFromCache_givenNoCachedConfig_expectFallbackConfigUsed() = runTest {
        // Null cached config must NOT skip restore — otherwise every reboot
        // would leave the device with no OS registrations until the next EXIT.
        val movementLoc = GeofenceLocation(latitude = 1.0, longitude = 2.0)
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastMovementTriggerLocation() } returns movementLoc
        every { store.getCachedConfig() } returns null
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("biz-1", 1.0, 2.0, 100f))
        every { store.getRegisteredIds() } returns emptySet()
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 1.0, 2.0, 100f))
        coEvery { manager.replaceGeofencesForBootRestore(any()) } returns Result.success(Unit)

        val result = repository.restoreFromCache()

        result.isSuccess shouldBeEqualTo true
        coVerify { manager.replaceGeofencesForBootRestore(any()) }
    }

    @Test
    fun restoreFromCache_givenMovementLocation_expectUsedAsEffectiveLocation() = runTest {
        // Boot restore must prefer the movement-trigger location over the anchor.
        val movementLoc = GeofenceLocation(latitude = 50.0, longitude = 60.0)
        val anchor = GeofenceLocation(latitude = 12.34, longitude = 56.78)
        val cached = listOf(GeofenceRegion("biz-1", 50.0, 60.0, 100f))
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastMovementTriggerLocation() } returns movementLoc
        every { store.getLastApiFetchLocation() } returns anchor
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns emptySet()
        every { distanceFilter.nearest(cached, 50.0, 60.0, any()) } returns cached
        coEvery { manager.replaceGeofencesForBootRestore(any()) } returns Result.success(Unit)

        repository.restoreFromCache()

        // Distance filter receives movementLoc coords, NOT the anchor's.
        verify { distanceFilter.nearest(cached, 50.0, 60.0, any()) }
        verify(exactly = 0) { distanceFilter.nearest(any(), 12.34, 56.78, any()) }
        // Pins the boot-restore manager variant, not the normal one.
        coVerify { manager.replaceGeofencesForBootRestore(any()) }
        coVerify(exactly = 0) { manager.replaceGeofences(any(), any()) }
        coVerify(exactly = 0) { apiService.fetchGeofences(any(), any()) }
    }

    @Test
    fun restoreFromCache_givenNoMovementLocationButAnchor_expectFallbackToAnchor() = runTest {
        // Older cache (or first ever boot after this PR ships): no movement-trigger
        // location yet. Fall back to the anchor so we still restore something.
        val anchor = GeofenceLocation(latitude = 12.34, longitude = 56.78)
        val cached = listOf(GeofenceRegion("biz-1", 12.34, 56.78, 100f))
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastMovementTriggerLocation() } returns null
        every { store.getLastApiFetchLocation() } returns anchor
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns emptySet()
        every { distanceFilter.nearest(cached, 12.34, 56.78, any()) } returns cached
        coEvery { manager.replaceGeofencesForBootRestore(any()) } returns Result.success(Unit)

        repository.restoreFromCache()

        verify { distanceFilter.nearest(cached, 12.34, 56.78, any()) }
        coVerify { manager.replaceGeofencesForBootRestore(any()) }
    }

    @Test
    fun restoreFromCache_givenSuccess_expectAnchorAndTimestampNotRewritten() = runTest {
        // Local-refresh-style restore must not bump the anchor or sync timestamp;
        // those belong to Tier B (remote fetch) only.
        val movementLoc = GeofenceLocation(50.0, 60.0)
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastMovementTriggerLocation() } returns movementLoc
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("biz-1", 50.0, 60.0, 100f))
        every { store.getRegisteredIds() } returns emptySet()
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 50.0, 60.0, 100f))
        coEvery { manager.replaceGeofencesForBootRestore(any()) } returns Result.success(Unit)

        repository.restoreFromCache()

        verify(exactly = 0) { store.saveLastApiFetchLocation(any()) }
        verify(exactly = 0) { store.setLastSyncTimestamp(any()) }
    }

    @Test
    fun restoreFromCache_givenRefreshInFlight_expectStillRunsViaBootRestoreVariant() = runTest {
        // After a reboot GMS is wiped but persisted registeredIds aren't, so a
        // concurrent refresh would skip business as "unchanged" via the equality
        // diff and leave GMS empty. Boot-restore must run regardless of the gate,
        // via the no-diff replaceGeofencesForBootRestore.
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastMovementTriggerLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns emptySet()
        every { store.getLastSyncTimestamp() } returns null
        coEvery { apiService.fetchGeofences(any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns cached
        coEvery { manager.replaceGeofences(any(), any()) } coAnswers {
            // Hold the refresh path long enough that restoreFromCache enters mid-flight.
            delay(100)
            Result.success(Unit)
        }
        coEvery { manager.replaceGeofencesForBootRestore(any()) } returns Result.success(Unit)

        coroutineScope {
            launch { repository.refresh(latitude = 0.0, longitude = 0.0) }
            launch {
                delay(20)
                repository.restoreFromCache()
            }
        }

        // Without the bypass, the gate would have deduped restoreFromCache and
        // replaceGeofencesForBootRestore would never have been called.
        coVerify { manager.replaceGeofencesForBootRestore(any()) }
    }

    private fun sampleConfig(
        remoteFetchRefreshExpiry: Long = 86_400_000L
    ): GeofenceConfig = GeofenceConfig(
        localRefreshTriggerRadius = 1_000f,
        remoteFetchRefreshTriggerRadius = 5_000f,
        remoteFetchRefreshExpiry = remoteFetchRefreshExpiry,
        duplicateEventsExpiry = 3_600_000L,
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
                "remote_fetch_refresh_expiry_time": 86400000,
                "duplicate_events_expiry_time": 3600000,
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
