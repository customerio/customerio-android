package io.customer.geofence

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.api.GeofenceApiResponse
import io.customer.geofence.api.GeofenceApiService
import io.customer.geofence.api.toDomainRegions
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Clock
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
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
    private val cooldownFilter: GeofenceCooldownFilter = mockk(relaxed = true)
    private val transitionEmitter: GeofenceTransitionEmitter = mockk(relaxed = true)
    private val clock: Clock = mockk(relaxed = true)
    private val packageInfo: GeofencePackageInfo = mockk {
        every { lastUpdateTimeMs() } returns null
    }
    private val logger: GeofenceLogger = mockk(relaxed = true)
    private val jsonSerializer = GeofenceJsonSerializer()

    private lateinit var repository: GeofenceRepositoryImpl

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { })
        // Default: mirror real time so tests using relative timestamps work
        // without churn. Override for deterministic timing.
        every { clock.currentTimeMillis() } answers { System.currentTimeMillis() }
        // The relaxed mock would answer false, which is the "an identify landed mid-pass" branch.
        // Default to the ordinary outcome so only the tests that mean to exercise a refusal do.
        every { store.saveRoutableRegisteredIdsIfCurrent(any(), any()) } returns true
        repository = buildRepository()
    }

    private fun buildRepository() = GeofenceRepositoryImpl(
        apiService = apiService,
        store = store,
        distanceFilter = distanceFilter,
        manager = manager,
        secureUserStore = secureUserStore,
        cooldownFilter = cooldownFilter,
        transitionEmitter = transitionEmitter,
        clock = clock,
        packageInfo = packageInfo,
        logger = logger
    )

    @Test
    fun refresh_givenRecentSuccessfulSyncAndNotForced_expectSkipApiCall() = runTest {
        // Repeated identify within the freshness window must not hit the API.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L // 1 min ago
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getLastMovementTriggerLocation() } returns GeofenceLocation(0.0, 0.0)

        val result = repository.refresh(latitude = 0.0, longitude = 0.0)

        result.isSuccess shouldBeEqualTo true
        coVerify(exactly = 0) { apiService.fetchGeofences(any()) }
        verify { logger.logSyncSkippedFresh() }
    }

    @Test
    fun refresh_givenFreshCacheButOsRegsWiped_expectLocalRefreshFromCache() = runTest {
        // Safety net: cache is still time-fresh but no OS regs are live
        // (registeredIds empty) — re-register from cache rather than skip, else
        // nothing is monitored until the stale window expires. (Sign-out clears
        // the freshness timestamp, so the sign-out path takes REMOTE, not this
        // branch.)
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns emptySet()
        every { store.getCachedConfig() } returns sampleConfig()
        every { distanceFilter.nearest(cached, any(), any(), any(), any()) } returns cached
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 0) { apiService.fetchGeofences(any()) }
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
        every { distanceFilter.nearest(cached, any(), any(), any(), any()) } returns cached
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 0) { apiService.fetchGeofences(any()) }
        coVerify { manager.replaceGeofences(any(), any()) }
    }

    @Test
    fun refresh_givenMovedBeyondTriggerSinceLastRegistration_expectLocalRerank() = runTest {
        // refresh() catches a movement EXIT missed while the app was dead: the device is beyond the
        // trigger radius from where the nearest-N was last ranked, so re-rank locally — and not
        // remotely, since it's still within the remote radius and time-fresh. Mode-independent.
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L // time-fresh
        every { store.getCachedConfig() } returns sampleConfig() // local 1 km, remote 5 km
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getLastMovementTriggerLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns setOf("biz-1") // regs intact
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns cached
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        // ~2.2 km from the last registration: beyond the 1 km trigger, within the 5 km remote radius.
        repository.refresh(latitude = 0.02, longitude = 0.0)

        coVerify(exactly = 0) { apiService.fetchGeofences(any()) } // no remote fetch
        coVerify { manager.replaceGeofences(any(), any()) } // local re-rank
        verify(exactly = 0) { logger.logSyncSkippedFresh() }
    }

    @Test
    fun refresh_givenWithinTriggerSinceLastRegistration_expectSkip() = runTest {
        // The complement: a move smaller than the trigger radius leaves the ranking valid, so a
        // time-fresh refresh with regs intact skips — the live movement trigger hasn't fired yet.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L
        every { store.getCachedConfig() } returns sampleConfig() // local 1 km, remote 5 km
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getLastMovementTriggerLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { store.getRegisteredIds() } returns setOf("biz-1") // regs intact

        // ~550 m from the last registration: within the 1 km trigger radius.
        repository.refresh(latitude = 0.005, longitude = 0.0)

        coVerify(exactly = 0) { apiService.fetchGeofences(any()) }
        coVerify(exactly = 0) { manager.replaceGeofences(any(), any()) }
        verify { logger.logSyncSkippedFresh() }
    }

    @Test
    fun refresh_givenNeverSynced_expectRemoteFetchWithLocation() = runTest {
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns null // never synced -> remote fetch
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 37.7749, longitude = -122.4194)

        // The device location is sent to the API.
        coVerify { apiService.fetchGeofences(GeofenceLocation(37.7749, -122.4194)) }
    }

    @Test
    fun refresh_givenFreshButMovedBeyondFetchRadius_expectRemoteFetchWithLocation() = runTest {
        // Even within the freshness window, moving past the fetch radius makes the cached set no
        // longer nearby -> re-fetch.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L // time-fresh
        every { store.getCachedConfig() } returns sampleConfig() // remoteFetchRefreshTriggerRadius = 5 km
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        // 1° latitude ≈ 111 km from the anchor — beyond the 5 km fetch radius.
        repository.refresh(latitude = 1.0, longitude = 0.0)

        coVerify { apiService.fetchGeofences(GeofenceLocation(1.0, 0.0)) }
    }

    @Test
    fun handleMovement_givenMovedBeyondFetchRadius_expectRemoteFetchWithLocation() = runTest {
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getCachedConfig() } returns sampleConfig() // remoteFetchRefreshTriggerRadius = 5 km
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        // 1° latitude ≈ 111 km, far beyond the 5 km fetch radius -> re-fetch from server.
        repository.handleMovement(latitude = 1.0, longitude = 0.0)

        coVerify { apiService.fetchGeofences(GeofenceLocation(1.0, 0.0)) }
    }

    @Test
    fun handleMovement_givenRemoteFetchFails_expectMovementTriggerRearmedAtCurrentFix() = runTest {
        // A failed pass leaves the trigger where the device already exited, so nothing fires again.
        val error = IOException("network down")
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getCachedRegions() } returns emptyList()
        every { store.getRegisteredIds() } returns emptySet()
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { apiService.fetchGeofences(any()) } returns Result.failure(error)
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        val result = repository.handleMovement(latitude = 1.0, longitude = 0.0)

        result.isFailure shouldBeEqualTo true
        result.exceptionOrNull() shouldBeEqualTo error
        coVerify { manager.replaceGeofences(any(), any()) }
        verify { store.saveLastMovementTriggerLocation(GeofenceLocation(1.0, 0.0)) }
        verify { logger.logMovementRearmedAfterFailedRefresh() }
        // Anchor and freshness stay untouched, so the next EXIT still fetches remotely rather than
        // treating the re-rank as a successful sync.
        verify(exactly = 0) { store.saveLastApiFetchLocation(any()) }
        verify(exactly = 0) { store.setLastSyncTimestamp(any()) }
    }

    @Test
    fun handleMovement_givenMovedWithinFetchRadius_expectLocalReRankNoFetch() = runTest {
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getCachedConfig() } returns sampleConfig() // 5 km fetch radius
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns setOf("biz-1")
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns cached
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        // 0.01° latitude ≈ 1.1 km, within the 5 km fetch radius -> local re-rank only.
        repository.handleMovement(latitude = 0.01, longitude = 0.0)

        coVerify(exactly = 0) { apiService.fetchGeofences(any()) }
        coVerify { manager.replaceGeofences(any(), any()) }
    }

    @Test
    fun refresh_givenFreshAndNotMoved_expectSkip() = runTest {
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getLastMovementTriggerLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { store.getRegisteredIds() } returns setOf("biz-1") // regs intact -> nothing to do

        repository.refresh(latitude = 0.0, longitude = 0.0) // same as anchor

        coVerify(exactly = 0) { apiService.fetchGeofences(any()) }
        coVerify(exactly = 0) { manager.replaceGeofences(any(), any()) }
        verify { logger.logSyncSkippedFresh() }
    }

    @Test
    fun refresh_givenPastLocalRadiusFromFetchButAtLastRegistration_expectSkip() = runTest {
        // Ranking staleness is measured from the last registration (movement-trigger center), NOT the
        // last API fetch. The device sits ~2.2 km from the fetch anchor — beyond the 1 km local radius
        // but within the 5 km fetch radius (so no remote re-fetch) — yet exactly where it was last
        // re-ranked, so the ranking is current → SKIP. Guards against measuring from the fetch anchor.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L // time-fresh
        every { store.getCachedConfig() } returns sampleConfig() // local 1 km, remote 5 km
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0) // fetch anchor
        every { store.getLastMovementTriggerLocation() } returns GeofenceLocation(0.02, 0.0) // last re-rank
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("biz-1", 0.02, 0.0, 100f))
        every { store.getRegisteredIds() } returns setOf("biz-1") // regs intact

        // At the last-registration point (0 m from it), but ~2.2 km from the fetch anchor.
        repository.refresh(latitude = 0.02, longitude = 0.0)

        coVerify(exactly = 0) { apiService.fetchGeofences(any()) }
        coVerify(exactly = 0) { manager.replaceGeofences(any(), any()) }
        verify { logger.logSyncSkippedFresh() }
    }

    @Test
    fun refresh_givenStaleLastSync_expectApiCalled() = runTest {
        // Last sync older than threshold => proceed with refresh.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns
            System.currentTimeMillis() - GeofenceConstants.STALE_THRESHOLD_MS - 1_000L
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify { apiService.fetchGeofences(any()) }
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
        coVerify(exactly = 0) { apiService.fetchGeofences(any()) }
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
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify { apiService.fetchGeofences(any()) }
    }

    @Test
    fun refresh_givenNoPreviousSync_expectApiCalled() = runTest {
        // First-ever sync: no timestamp, threshold check is a no-op, proceed.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns null
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify { apiService.fetchGeofences(any()) }
    }

    @Test
    fun refresh_givenNoUserId_expectSkipAndSuccessAndNoApiCall() = runTest {
        every { secureUserStore.getUserId() } returns null

        val result = repository.refresh(latitude = 12.34, longitude = 56.78)

        result.isSuccess shouldBeEqualTo true
        coVerify(exactly = 0) { apiService.fetchGeofences(any()) }
        coVerify(exactly = 0) { manager.replaceGeofences(any(), any()) }
        verify(exactly = 0) { store.saveRegisteredIds(any()) }
        verify { logger.logSyncSkipped(match { it.contains("no identified user") }) }
    }

    @Test
    fun refresh_givenBlankUserId_expectSkipAndSuccess() = runTest {
        every { secureUserStore.getUserId() } returns "   "

        val result = repository.refresh(latitude = 0.0, longitude = 0.0)

        result.isSuccess shouldBeEqualTo true
        coVerify(exactly = 0) { apiService.fetchGeofences(any()) }
    }

    @Test
    fun refresh_givenApiFailure_expectFailurePropagatedAndNoPersistOrRegister() = runTest {
        val error = IOException("network down")
        every { secureUserStore.getUserId() } returns "user-42"
        coEvery { apiService.fetchGeofences(any()) } returns Result.failure(error)

        val result = repository.refresh(latitude = 12.34, longitude = 56.78)

        result.isFailure shouldBeEqualTo true
        result.exceptionOrNull() shouldBeEqualTo error
        verify { logger.logSyncFailed(match { it?.contains("network down") == true }) }
        verify(exactly = 0) { store.saveRegisteredIds(any()) }
        verify(exactly = 0) { store.setLastSyncTimestamp(any()) }
        coVerify(exactly = 0) { manager.replaceGeofences(any(), any()) }
    }

    @Test
    fun refresh_givenResponseMappingThrows_expectFailureNotEmptySuccess() = runTest {
        // Unusable response fails the refresh; nothing registered, removed, or persisted.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns null
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        mockkStatic("io.customer.geofence.api.GeofenceApiResponseKt")
        try {
            every { any<GeofenceApiResponse>().toDomainRegions() } throws IllegalStateException("mapper defect")

            val result = repository.refresh(latitude = 12.34, longitude = 56.78)

            result.isFailure shouldBeEqualTo true
            verify { logger.logSyncFailed(match { it?.contains("mapper defect") == true }) }
            coVerify(exactly = 0) { manager.replaceGeofences(any(), any()) }
            coVerify(exactly = 0) { manager.removeGeofencesByIds(any()) }
            verify(exactly = 0) { store.saveCachedRegions(any()) }
            verify(exactly = 0) { store.saveRegisteredIds(any()) }
        } finally {
            unmockkStatic("io.customer.geofence.api.GeofenceApiResponseKt")
        }
    }

    @Test
    fun refresh_givenSuccessWithBusiness_expectMovementTriggerLocationPersisted() = runTest {
        // Persisted point is the user's lat/lng at registration time — boot
        // restore reads it later as the effective coordinates.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)
        val capturedLoc = slot<GeofenceLocation>()
        every { store.saveLastMovementTriggerLocation(capture(capturedLoc)) } returns Unit

        repository.refresh(latitude = 12.34, longitude = 56.78)

        capturedLoc.captured shouldBeEqualTo GeofenceLocation(latitude = 12.34, longitude = 56.78)
    }

    @Test
    fun refresh_givenKillSwitchConfig_expectNothingRegisteredAndMovementTriggerLocationCleared() = runTest {
        // maxBusinessGeofences = 0 is the explicit server kill switch — unregister
        // everything, including the movement trigger and its stored location.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(emptyResponse(maxBusinessGeofences = 0))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns emptyList()
        val captured = slot<List<GeofenceRegion>>()
        coEvery { manager.replaceGeofences(capture(captured), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 12.34, longitude = 56.78)

        captured.captured.shouldBeEmpty()
        verify(exactly = 0) { store.saveLastMovementTriggerLocation(any()) }
        verify { store.clearLastMovementTriggerLocation() }
        // Region count alone can't distinguish this from an empty-but-still-monitoring sync.
        verify { logger.logSyncSucceeded(0, movementTriggerRegistered = false) }
    }

    @Test
    fun refresh_givenEmptyResponseWithPositiveBudget_expectMovementTriggerKept() = runTest {
        // `/nearest` is distance-capped, so an empty list means "none nearby", not "feature
        // off" — a road trip through a fence-free area must keep the movement trigger
        // registered so a later EXIT re-fetches; otherwise geofencing dies until the next
        // app launch.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(emptyResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns emptyList()
        val captured = slot<List<GeofenceRegion>>()
        coEvery { manager.replaceGeofences(capture(captured), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 12.34, longitude = 56.78)

        captured.captured.map { it.id } shouldBeEqualTo listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID)
        verify { store.saveLastMovementTriggerLocation(GeofenceLocation(12.34, 56.78)) }
        verify(exactly = 0) { store.clearLastMovementTriggerLocation() }
    }

    @Test
    fun refresh_givenGeofencesExistButAllBeyondCap_expectMovementTriggerRegisteredAndLocationSaved() = runTest {
        // Geofences exist but none qualify right now (all beyond maxMonitoringDistance). We must
        // still register the movement trigger so an EXIT re-ranks and re-registers them as the
        // device approaches — unlike the truly-empty case, which registers nothing.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3)) // non-empty fetched set
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns emptyList() // none near
        val captured = slot<List<GeofenceRegion>>()
        coEvery { manager.replaceGeofences(capture(captured), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 12.34, longitude = 56.78)

        // Only the movement trigger is registered; no business geofences qualified.
        captured.captured.map { it.id } shouldBeEqualTo listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID)
        verify { store.saveLastMovementTriggerLocation(GeofenceLocation(12.34, 56.78)) }
        verify(exactly = 0) { store.clearLastMovementTriggerLocation() }
        verify { logger.logSyncSucceeded(0, movementTriggerRegistered = true) }
    }

    @Test
    fun refresh_givenManagerAddFails_expectMovementTriggerLocationNotPersisted() = runTest {
        // Persistence is gated on add success — a failed registration must leave the
        // last-known good movement location intact (next refresh retries).
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.failure(RuntimeException("boom"))

        repository.refresh(latitude = 12.34, longitude = 56.78)

        verify(exactly = 0) { store.saveLastMovementTriggerLocation(any()) }
    }

    @Test
    fun refreshFromLiveFix_givenRegistrationSucceeds_expectContainmentSeededFromGeometryNotFromEnterEvents() = runTest {
        // Initial-ENTER synthesis is suppressed after a reboot/app update, so no ENTER is emitted
        // for a fence the device is already inside. Without seeding containment here, that fence's
        // later genuine EXIT would look unentered and be dropped by the guard.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        val inside = GeofenceRegion("biz-inside", 0.0, 0.0, 500f)
        val outside = GeofenceRegion("biz-outside", 1.0, 1.0, 100f)
        coEvery { apiService.fetchGeofences(any()) } returns Result.success(sampleResponse(maxBusinessGeofences = 5))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns listOf(inside, outside)
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        val result = repository.refreshFromLiveFix(latitude = 0.0, longitude = 0.0)

        result.isSuccess shouldBeEqualTo true
        // Only the fence whose radius actually contains the device is seeded; the far one is not,
        // so a phantom EXIT for it is still dropped.
        verify {
            store.reconcileEnteredIds(
                registeredIds = any(),
                inside = setOf("biz-inside"),
                sinceEpoch = any()
            )
        }
    }

    @Test
    fun refreshFromLiveFix_givenFixInsideAFence_expectSeededByExactPointCheck() = runTest {
        // Deliberate: containment is an exact point check with no accuracy margin. Requiring an
        // error circle to fit would make a fence smaller than the fix unjudgable at any distance,
        // so a low-power fix could never seed or fire the initial-ENTER backstop.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        val fence = GeofenceRegion("biz-edge", metersOfLatitude(60), 0.0, 100f)
        coEvery { apiService.fetchGeofences(any()) } returns Result.success(sampleResponse(maxBusinessGeofences = 5))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns listOf(fence)
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refreshFromLiveFix(
            latitude = 0.0,
            longitude = 0.0
        )

        // Non-null, so a live pass still ends the no-record grace with a real reading rather than
        // arming the unmatched-EXIT guard off a fix that decided nothing.
        verify { store.reconcileEnteredIds(registeredIds = any(), inside = setOf("biz-edge"), sinceEpoch = any()) }
    }

    @Test
    fun refreshFromLiveFix_givenFixJustOutsideAReRegisteredFence_expectMarkRetired() = runTest {
        // No accuracy margin on the outside test either. Keeping the mark while the device is
        // "not certainly outside" would leave it in place when the device is genuinely outside the
        // edited circle, and that mark then drops the next real arrival as redundant.
        every { secureUserStore.getUserId() } returns "user-42"
        val fence = GeofenceRegion("biz-edge", metersOfLatitude(120), 0.0, 100f)
        every { store.getRegisteredIds() } returns setOf("biz-edge")
        coEvery { apiService.fetchGeofences(any()) } returns Result.success(sampleResponse(maxBusinessGeofences = 5))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns listOf(fence)
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refreshFromLiveFix(
            latitude = 0.0,
            longitude = 0.0
        )

        verify {
            store.reconcileEnteredIds(
                registeredIds = any(),
                inside = emptySet(),
                sinceEpoch = any(),
                resetIds = setOf("biz-edge")
            )
        }
    }

    @Test
    fun refresh_givenAnchorInsideAFence_expectNoSeedingNoSynthesis() = runTest {
        // refresh() runs off the persisted anchor — where the device once was. Seeding containment
        // or synthesizing an ENTER from it would resurrect an exited visit or report an arrival at
        // a place the device left days ago. Anchor passes only rank and prune.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        val inside = GeofenceRegion("biz-inside", 0.0, 0.0, 500f)
        coEvery { apiService.fetchGeofences(any()) } returns Result.success(sampleResponse(maxBusinessGeofences = 5))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns listOf(inside)
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        val result = repository.refresh(latitude = 0.0, longitude = 0.0)

        result.isSuccess shouldBeEqualTo true
        verify {
            store.reconcileEnteredIds(
                registeredIds = any(),
                inside = null,
                sinceEpoch = any(),
                resetIds = emptySet()
            )
        }
        coVerify(exactly = 0) { transitionEmitter.emit(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun refreshFromLiveFix_givenTransitionWhileQueuedForTheSlot_expectEpochFromBeforeTheWait() = runTest {
        // The epoch belongs to the fix, so it has to be read before the slot wait. A crossing
        // reported while this pass queues behind a holder postdates the fix; reading the epoch
        // after the wait would fold that crossing into the baseline and let the seed overrule it.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        every { store.containmentEpoch() } returns 3L
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns listOf(GeofenceRegion("biz-1", 0.0, 0.0, 500f))
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)
        coEvery { apiService.fetchGeofences(any()) } coAnswers {
            // The holder occupies the slot; a transition lands while the live-fix pass queues.
            delay(20.seconds)
            every { store.containmentEpoch() } returns 9L
            Result.success(sampleResponse(maxBusinessGeofences = 5))
        }

        val holder = launch { repository.refresh(latitude = 0.0, longitude = 0.0) }
        runCurrent()
        repository.refreshFromLiveFix(latitude = 0.0, longitude = 0.0)
        holder.join()

        verify {
            store.reconcileEnteredIds(
                registeredIds = any(),
                inside = setOf("biz-1"),
                sinceEpoch = 3L,
                resetIds = any()
            )
        }
    }

    @Test
    fun refresh_givenExitClaimedWhileRegistering_expectEpochFromBeforeTheAwait() = runTest {
        // The seed must be judged against the containment state as of this sync's fix, not as of
        // whenever GMS finished. Registration awaits GMS for seconds, and an EXIT landing in that
        // window is newer evidence than the geometry.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        every { store.containmentEpoch() } returns 5L
        coEvery { apiService.fetchGeofences(any()) } returns Result.success(sampleResponse(maxBusinessGeofences = 5))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-inside", 0.0, 0.0, 500f))
        coEvery { manager.replaceGeofences(any(), any()) } coAnswers {
            // A transition claims an exit mid-registration, advancing the epoch.
            every { store.containmentEpoch() } returns 7L
            Result.success(Unit)
        }

        repository.refresh(latitude = 0.0, longitude = 0.0)

        verify { store.reconcileEnteredIds(registeredIds = any(), inside = any(), sinceEpoch = 5L) }
    }

    @Test
    fun refresh_givenRegistrationFails_expectContainmentNotSeeded() = runTest {
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any()) } returns Result.success(sampleResponse(maxBusinessGeofences = 5))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-inside", 0.0, 0.0, 500f))
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.failure(IOException("gms down"))

        repository.refresh(latitude = 0.0, longitude = 0.0)

        // Nothing is monitored, so claiming containment would let a later phantom EXIT through.
        verify(exactly = 0) { store.reconcileEnteredIds(any(), any(), any()) }
        verify(exactly = 0) { store.pruneEmittedEnterIds(any()) }
    }

    @Test
    fun refresh_givenRegistrationSucceeds_expectEmittedEnterPrunedToRegisteredSet() = runTest {
        // The reported-ENTER marks must be pruned to the same snapshot the registrations are, or a
        // fence evicted while the device is inside keeps a mark no EXIT will ever clear.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any()) } returns Result.success(sampleResponse(maxBusinessGeofences = 5))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-inside", 0.0, 0.0, 500f))
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        val pruned = slot<Set<String>>()
        verify { store.pruneEmittedEnterIds(capture(pruned)) }
        pruned.captured shouldContain "biz-inside"
    }

    @Test
    fun refresh_givenSuccessfulRegistration_expectRoutingArmedForTheSameIds() = runTest {
        // beginUserSession writes an explicit empty routable set and getRoutableRegisteredIds stops
        // falling back once that key exists, so a refresh that registers without arming routing
        // leaves the receiver treating every live ID as unknown and removing it from the OS.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        every { store.userStateGeneration() } returns 7L
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3, localRefreshTriggerRadius = 1500f))
        every { distanceFilter.nearest(any(), 12.34, 56.78, 3, any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        val captured = slot<List<GeofenceRegion>>()
        coEvery { manager.replaceGeofences(capture(captured), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 12.34, longitude = 56.78)

        val registered = captured.captured.map { it.id }.toSet()
        verify { store.saveRoutableRegisteredIdsIfCurrent(registered, 7L) }
    }

    @Test
    fun refresh_givenUserChangedBeforeRoutingWasArmed_expectSyncNotStampedFresh() = runTest {
        // The store refuses the stale write, so routing stays cleared for the new user. Stamping the
        // sync anyway would make that user's own refresh SKIP on this timestamp and never arm
        // routing, and its first callback would then remove every live fence from the OS.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        every { store.userStateGeneration() } returns 7L
        every { store.saveRoutableRegisteredIdsIfCurrent(any(), any()) } returns false
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3, localRefreshTriggerRadius = 1500f))
        every { distanceFilter.nearest(any(), 12.34, 56.78, 3, any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 12.34, longitude = 56.78)

        verify { logger.logSyncSkipped("user changed before routing could be armed") }
        verify(exactly = 0) { store.setLastSyncTimestamp(any()) }
    }

    @Test
    fun refresh_givenRoutingArmed_expectSyncStampedFresh() = runTest {
        // Control: the stamp is skipped because routing was refused, not because it never happens.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        every { store.userStateGeneration() } returns 7L
        every { store.saveRoutableRegisteredIdsIfCurrent(any(), any()) } returns true
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3, localRefreshTriggerRadius = 1500f))
        every { distanceFilter.nearest(any(), 12.34, 56.78, 3, any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 12.34, longitude = 56.78)

        verify { store.setLastSyncTimestamp(any()) }
    }

    @Test
    fun refresh_givenBusinessGeofences_expectMovementTriggerPrependedAndRegistered() = runTest {
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        val filtered = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3, localRefreshTriggerRadius = 1500f))
        every { distanceFilter.nearest(any(), 12.34, 56.78, 3, any()) } returns filtered
        val captured = slot<List<GeofenceRegion>>()
        coEvery { manager.replaceGeofences(capture(captured), any()) } returns Result.success(Unit)

        val result = repository.refresh(latitude = 12.34, longitude = 56.78)

        result.isSuccess shouldBeEqualTo true
        verify { store.setLastSyncTimestamp(any()) }
        verify { distanceFilter.nearest(any(), 12.34, 56.78, 3, any()) }
        verify { logger.logSyncSucceeded(filtered.size, movementTriggerRegistered = true) }
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
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3, localRefreshTriggerRadius = 1500f))
        every { distanceFilter.nearest(any(), 12.34, 56.78, 3, any()) } returns
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
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns
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
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 5))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns newBusiness
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
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 0) { manager.removeGeofencesByIds(any()) }
    }

    @Test
    fun refresh_givenDeviceRebootedSinceLastRegistration_expectAllBusinessReRegistered() = runTest {
        // Uptime regressed => device rebooted => GMS dropped every geofence. Re-register all
        // business (empty existing set) even though registeredIds still lists them, covering the
        // case where the BOOT_COMPLETED receiver never fired.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns setOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "biz-1")
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { store.getLastRegistrationUptime() } returns 10_000L
        every { clock.elapsedRealtime() } returns 5_000L
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 5))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        val existingSlot = slot<Set<String>>()
        coEvery { manager.replaceGeofences(any(), capture(existingSlot)) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        existingSlot.captured.shouldBeEmpty()
    }

    @Test
    fun refresh_givenNoRebootAndBusinessUnchanged_expectBusinessKept() = runTest {
        // Uptime advanced normally => no reboot => trust registeredIds and keep the unchanged
        // business geofence (skip re-upsert).
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns setOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "biz-1")
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { store.getLastRegistrationUptime() } returns 5_000L
        every { clock.elapsedRealtime() } returns 10_000L
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 5))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        val existingSlot = slot<Set<String>>()
        coEvery { manager.replaceGeofences(any(), capture(existingSlot)) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        existingSlot.captured shouldContainSame setOf("biz-1")
    }

    @Test
    fun refresh_givenFreshCacheButRebooted_expectLocalReRegisterInsteadOfSkip() = runTest {
        // Reboot after a fresh-cache launch: time-fresh + ranking-fresh + registeredIds intact would
        // normally SKIP, but GMS was wiped by the reboot. Uptime regression forces a LOCAL re-register
        // (no network) instead of leaving nothing monitored.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getLastMovementTriggerLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { store.getRegisteredIds() } returns setOf("biz-1")
        every { store.getLastRegistrationUptime() } returns 10_000L
        every { clock.elapsedRealtime() } returns 5_000L
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        val existingSlot = slot<Set<String>>()
        coEvery { manager.replaceGeofences(any(), capture(existingSlot)) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 0) { apiService.fetchGeofences(any()) }
        coVerify(exactly = 1) { manager.replaceGeofences(any(), any()) }
        existingSlot.captured.shouldBeEmpty()
    }

    @Test
    fun refresh_givenAppUpdatedSinceLastRegistration_expectAllBusinessReRegistered() = runTest {
        // Package lastUpdateTime changed => the app was updated, which can cancel the geofence
        // PendingIntent and drop OS registrations. Re-register all business (empty existing set)
        // even though registeredIds still lists them, then re-stamp the new update time.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns setOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "biz-1")
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { store.getLastRegistrationUptime() } returns 5_000L
        every { clock.elapsedRealtime() } returns 10_000L // no reboot
        every { store.getLastRegistrationPackageUpdateTime() } returns 1_000L
        every { packageInfo.lastUpdateTimeMs() } returns 2_000L // updated since
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 5))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        val existingSlot = slot<Set<String>>()
        coEvery { manager.replaceGeofences(any(), capture(existingSlot)) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        existingSlot.captured.shouldBeEmpty()
        verify { store.setLastRegistrationPackageUpdateTime(2_000L) }
    }

    @Test
    fun refresh_givenRegistrationsButNoPackageStamp_expectAllBusinessReRegistered() = runTest {
        // Upgrade migration: registrations from an SDK version that didn't stamp the package
        // update time predate stamping — the upgrade itself was an app update, so re-register.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns setOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "biz-1")
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { store.getLastRegistrationUptime() } returns 5_000L
        every { clock.elapsedRealtime() } returns 10_000L // no reboot
        every { store.getLastRegistrationPackageUpdateTime() } returns null
        every { packageInfo.lastUpdateTimeMs() } returns 2_000L
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 5))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        val existingSlot = slot<Set<String>>()
        coEvery { manager.replaceGeofences(any(), capture(existingSlot)) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        existingSlot.captured.shouldBeEmpty()
        verify { store.setLastRegistrationPackageUpdateTime(2_000L) }
    }

    @Test
    fun refresh_givenNoAppUpdateSinceLastRegistration_expectBusinessKept() = runTest {
        // Matching update-time stamps => package untouched => trust registeredIds and keep the
        // unchanged business geofence (skip re-upsert).
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns setOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "biz-1")
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { store.getLastRegistrationUptime() } returns 5_000L
        every { clock.elapsedRealtime() } returns 10_000L // no reboot
        every { store.getLastRegistrationPackageUpdateTime() } returns 1_000L
        every { packageInfo.lastUpdateTimeMs() } returns 1_000L // unchanged
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 5))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        val existingSlot = slot<Set<String>>()
        coEvery { manager.replaceGeofences(any(), capture(existingSlot)) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        existingSlot.captured shouldContainSame setOf("biz-1")
    }

    @Test
    fun refresh_givenFreshCacheButAppUpdated_expectLocalReRegisterInsteadOfSkip() = runTest {
        // App update after a fresh-cache launch: time-fresh + ranking-fresh + registeredIds intact
        // would normally SKIP, but the update may have wiped GMS state. Force a LOCAL re-register
        // (no network) instead of leaving nothing monitored — the exact post-update launch path.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getLastMovementTriggerLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { store.getRegisteredIds() } returns setOf("biz-1")
        every { store.getLastRegistrationUptime() } returns 5_000L
        every { clock.elapsedRealtime() } returns 10_000L // no reboot
        every { store.getLastRegistrationPackageUpdateTime() } returns 1_000L
        every { packageInfo.lastUpdateTimeMs() } returns 2_000L // updated since
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        val existingSlot = slot<Set<String>>()
        coEvery { manager.replaceGeofences(any(), capture(existingSlot)) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 0) { apiService.fetchGeofences(any()) }
        coVerify(exactly = 1) { manager.replaceGeofences(any(), any()) }
        existingSlot.captured.shouldBeEmpty()
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
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns listOf(newRegion)
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
        verify { logger.logSyncSucceeded(1, movementTriggerRegistered = true) }
        // Persisted set includes the unremoved stale ID — next refresh will retry it.
        persisted.captured shouldContainSame
            setOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "biz-new", "biz-old")
    }

    @Test
    fun refresh_givenAllPreviousAbsentFromNew_expectBusinessRemovedButTriggerKept() = runTest {
        // No fences in this response: previously registered business IDs are removed as
        // stale, but the movement trigger stays so a later EXIT can re-fetch — the
        // distance-capped /nearest can't distinguish "none nearby" from "none exist".
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns setOf(
            GeofenceConstants.MOVEMENT_TRIGGER_ID,
            "biz-old"
        )
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(emptyResponse())
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)
        coEvery { manager.removeGeofencesByIds(any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        val staleSlot = slot<List<String>>()
        coVerify { manager.removeGeofencesByIds(capture(staleSlot)) }
        staleSlot.captured shouldContainSame listOf("biz-old")
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
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 5))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-new", 0.0, 0.0, 100f))
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.failure(error)

        val result = repository.refresh(latitude = 0.0, longitude = 0.0)

        result.isFailure shouldBeEqualTo true
        result.exceptionOrNull() shouldBeEqualTo error
        coVerify(exactly = 0) { manager.removeGeofencesByIds(any()) }
        verify(exactly = 0) { store.saveRegisteredIds(any()) }
        verify(exactly = 0) { store.setLastSyncTimestamp(any()) }
        verify(exactly = 0) { logger.logSyncSucceeded(any(), any()) }
    }

    @Test
    fun refresh_givenSecondCallWhileFirstInFlight_expectSecondDroppedAndOnlyOneApiCall() = runTest {
        // In-flight gate dedups concurrent triggers: the second refresh returns
        // success immediately without firing a redundant API call or attempting
        // a second OS registration.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns emptyList()

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
        coVerify(exactly = 1) { apiService.fetchGeofences(any()) }
        verify { logger.logSyncSkipped(match { it.contains("refresh already in progress") }) }
    }

    @Test
    fun refresh_givenInFlightGateCleared_expectSubsequentCallProceeds() = runTest {
        // Pins the contract that the in-flight gate is released even on failure,
        // so a follow-up refresh isn't permanently locked out.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any()) } returnsMany listOf(
            Result.failure(IOException("first call fails")),
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        )
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        val first = repository.refresh(latitude = 0.0, longitude = 0.0)
        val second = repository.refresh(latitude = 0.0, longitude = 0.0)

        first.isFailure shouldBeEqualTo true
        second.isSuccess shouldBeEqualTo true
        coVerify(exactly = 2) { apiService.fetchGeofences(any()) }
    }

    @Test
    fun refresh_givenUserChangesDuringApiCall_expectNoWriteToStoreOrManager() = runTest {
        // Defends against a reset()/sign-out racing with an in-flight refresh:
        // the userId recheck inside the state lock prevents writing the previous
        // user's geofences after a sign-out cleared state.
        every { secureUserStore.getUserId() } returnsMany listOf("user-A", "user-B")
        coEvery { apiService.fetchGeofences(any()) } returns
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
        coEvery { apiService.fetchGeofences(any()) } returns
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
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns listOf(region)
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
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns listOf(incoming)
        val existingSlot = slot<Set<String>>()
        coEvery { manager.replaceGeofences(any(), capture(existingSlot)) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        existingSlot.captured.shouldBeEmpty()
    }

    @Test
    fun refresh_givenRemoteFetchAndOnlyGeosetsDiffer_expectIdKeptNotReRegistered() = runTest {
        // Backend changed only biz-1's geoset membership, not its geometry. Geosets drive event
        // fan-out, not OS registration, so biz-1 stays in existingBusinessIds — avoiding a needless
        // GMS re-register (which would fire a spurious INITIAL ENTER).
        val cached = GeofenceRegion("biz-1", 1.0, 2.0, 100f, geosetIds = listOf("1"))
        val incoming = cached.copy(geosetIds = listOf("1", "2", "3"))
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns null
        every { store.getRegisteredIds() } returns setOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "biz-1")
        every { store.getCachedRegions() } returns listOf(cached)
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns listOf(incoming)
        val existingSlot = slot<Set<String>>()
        coEvery { manager.replaceGeofences(any(), capture(existingSlot)) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        existingSlot.captured shouldContainSame setOf("biz-1")
    }

    @Test
    fun refresh_givenRemoteFetchAndOnlyMetadataDiffers_expectIdKeptNotReRegistered() = runTest {
        // Metadata is event payload, not OS geometry — a metadata-only change must not force a GMS
        // re-register (which would fire a spurious INITIAL ENTER).
        val cached = GeofenceRegion("biz-1", 1.0, 2.0, 100f, metadata = mapOf("k" to JsonPrimitive("old")))
        val incoming = cached.copy(metadata = mapOf("k" to JsonPrimitive("new")))
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns null
        every { store.getRegisteredIds() } returns setOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "biz-1")
        every { store.getCachedRegions() } returns listOf(cached)
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns listOf(incoming)
        val existingSlot = slot<Set<String>>()
        coEvery { manager.replaceGeofences(any(), capture(existingSlot)) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        existingSlot.captured shouldContainSame setOf("biz-1")
    }

    @Test
    fun refresh_givenRemoteFetchAndOnlyNameDiffers_expectIdKeptNotReRegistered() = runTest {
        // A backend rename is event payload, not OS geometry — it must not force a re-register
        // (which would fire INITIAL_TRIGGER_ENTER and emit a synthetic enter for a device inside).
        val cached = GeofenceRegion("biz-1", 1.0, 2.0, 100f, name = "Old Name")
        val incoming = cached.copy(name = "New Name")
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns null
        every { store.getRegisteredIds() } returns setOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "biz-1")
        every { store.getCachedRegions() } returns listOf(cached)
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns listOf(incoming)
        val existingSlot = slot<Set<String>>()
        coEvery { manager.replaceGeofences(any(), capture(existingSlot)) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        existingSlot.captured shouldContainSame setOf("biz-1")
    }

    @Test
    fun refresh_givenRemoteFetchAndOnlyLastUpdatedDiffers_expectIdKeptNotReRegistered() = runTest {
        // The backend can bump `last_updated` on an unchanged fence; that's bookkeeping, not geometry,
        // so it must not re-register every fence on every sync (each would fire a spurious INITIAL enter).
        val cached = GeofenceRegion("biz-1", 1.0, 2.0, 100f, lastUpdated = 1_000L)
        val incoming = cached.copy(lastUpdated = 2_000L)
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns null
        every { store.getRegisteredIds() } returns setOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "biz-1")
        every { store.getCachedRegions() } returns listOf(cached)
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns listOf(incoming)
        val existingSlot = slot<Set<String>>()
        coEvery { manager.replaceGeofences(any(), capture(existingSlot)) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        existingSlot.captured shouldContainSame setOf("biz-1")
    }

    @Test
    fun reset_givenManagerSucceeds_expectUserScopedStateClearedAndWorkspaceCachePreserved() = runTest {
        // Sign-out cleanup: drop OS registrations + wipe user-scoped state
        // (anchor, movement-trigger location, registered IDs, freshness
        // timestamp). Cached regions/config survive; the dropped timestamp
        // makes the next login re-fetch.
        every { secureUserStore.getUserId() } returns null
        coEvery { manager.clearAll() } returns Result.success(Unit)

        val result = repository.reset()

        result.isSuccess shouldBeEqualTo true
        coVerifyOrder {
            manager.clearAll()
            store.clearUserScopedState()
        }
        verify { cooldownFilter.clearAll() }
        verify(exactly = 0) { store.clearAll() }
    }

    @Test
    fun reset_givenUserSignedInAtResetTime_expectSkipWipe() = runTest {
        // A user is signed in when reset() runs — a fast account switch (A→B) or a same-user
        // clearIdentify+identify. Geofences are workspace-scoped, so the active user reuses the
        // existing registration and their identify-sync reconciles it; reset must NOT tear it
        // down (that would race the sync and drop coverage). Holds regardless of what the racing
        // refresh decided (REMOTE/LOCAL/SKIP), since reset keys only off the current user.
        every { secureUserStore.getUserId() } returns "user-B"

        val result = repository.reset()

        result.isSuccess shouldBeEqualTo true
        coVerify(exactly = 0) { manager.clearAll() }
        verify(exactly = 0) { store.clearUserScopedState() }
        // Registrations survive, so their suppression windows must too — wiping the
        // cooldown here would let still-registered fences re-emit inside the window.
        verify(exactly = 0) { cooldownFilter.clearAll() }
        verify { logger.logSyncSkipped(match { it.contains("reset superseded") }) }
    }

    @Test
    fun reset_givenEmptyCurrentUser_expectWipeProceeds() = runTest {
        // Empty userId is "not identified" (matches isUserIdentified), so it's a genuine sign-out
        // and must wipe — same as a null user.
        every { secureUserStore.getUserId() } returns ""
        coEvery { manager.clearAll() } returns Result.success(Unit)

        val result = repository.reset()

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

        val result = repository.reset()

        result.isFailure shouldBeEqualTo true
        result.exceptionOrNull() shouldBeEqualTo error
        verify(exactly = 0) { store.clearUserScopedState() }
        verify(exactly = 0) { store.clearAll() }
        // Cooldown is user-scoped suppression, not registration state: it's wiped on a genuine
        // sign-out even when the OS clear fails, so the next user can't inherit stale windows.
        verify(exactly = 1) { cooldownFilter.clearAll() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun refresh_givenIdentifyDuringInFlightResetWipe_expectDecisionWaitsAndRefreshes() = runTest {
        // Reset passes its no-user check and suspends on the GMS clear; user B identifies
        // meanwhile. The refresh decision must wait for the reset and see the wiped state —
        // deciding on pre-wipe state would SKIP and leave B unmonitored after the wipe lands.
        every { secureUserStore.getUserId() } returns null
        // Pre-wipe state that would produce SKIP: fresh sync, registered IDs present, no movement.
        every { store.getLastSyncTimestamp() } answers { clock.currentTimeMillis() }
        every { store.getRegisteredIds() } returns setOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "biz-1")
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("biz-1", 1.0, 2.0, 100f))
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getLastMovementTriggerLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getLastRegistrationUptime() } returns null
        // Model the wipe on the mock so a post-wipe decision sees stale state.
        every { store.clearUserScopedState() } answers {
            every { store.getLastSyncTimestamp() } returns null
            every { store.getRegisteredIds() } returns emptySet()
        }
        val gmsClear = CompletableDeferred<Result<Unit>>()
        coEvery { manager.clearAll() } coAnswers { gmsClear.await() }
        coEvery { apiService.fetchGeofences(any()) } returns Result.failure(IOException("test: fetch reached"))

        val resetJob = launch { repository.reset() }
        runCurrent() // reset holds the state lock, suspended on the GMS clear
        every { secureUserStore.getUserId() } returns "user-B"
        val refreshJob = launch { repository.refresh(latitude = 0.0, longitude = 0.0) }
        runCurrent() // refresh's decision must now be blocked behind the lock
        gmsClear.complete(Result.success(Unit))
        advanceUntilIdle()
        resetJob.join()
        refreshJob.join()

        // The decision saw the wiped (stale) state and refreshed instead of skipping.
        verify(exactly = 0) { logger.logSyncSkippedFresh() }
        coVerify(exactly = 1) { apiService.fetchGeofences(any()) }
        verify { store.clearUserScopedState() }
    }

    // ---------- handleMovement / tier dispatch ----------

    @Test
    fun handleMovement_givenNoUserId_expectSkipAndNoOp() = runTest {
        every { secureUserStore.getUserId() } returns null

        val result = repository.handleMovement(latitude = 0.0, longitude = 0.0)

        result.isSuccess shouldBeEqualTo true
        coVerify(exactly = 0) { apiService.fetchGeofences(any()) }
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
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.handleMovement(latitude = 1.0, longitude = 2.0)

        coVerify { apiService.fetchGeofences(any()) }
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
        every { distanceFilter.nearest(cached, any(), any(), any(), any()) } returns cached
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        // ~111m from anchor — well within the fallback 5km threshold.
        repository.handleMovement(latitude = 0.0, longitude = 0.001)

        coVerify(exactly = 0) { apiService.fetchGeofences(any()) }
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
        every { distanceFilter.nearest(cached, any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.handleMovement(latitude = 0.0, longitude = 0.001)

        coVerify(exactly = 0) { apiService.fetchGeofences(any()) }
        coVerify { manager.replaceGeofences(any(), any()) }
        verify { distanceFilter.nearest(cached, 0.0, 0.001, any(), any()) }
    }

    @Test
    fun handleMovement_givenLocalRerankSucceeds_expectCacheAndAnchorAndTimestampNotUpdated() = runTest {
        // Tier A reuses the existing anchor so the 5km threshold keeps measuring from the same point.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { store.getRegisteredIds() } returns emptySet()
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.handleMovement(latitude = 0.0, longitude = 0.001)

        verify(exactly = 0) { store.saveCachedRegions(any()) }
        verify(exactly = 0) { store.saveCachedConfig(any()) }
        verify(exactly = 0) { store.saveLastApiFetchLocation(any()) }
        verify(exactly = 0) { store.setLastSyncTimestamp(any()) }
    }

    @Test
    fun handleMovement_givenSecondCallWhileFirstInFlight_expectSerializedNotDropped() = runTest {
        // Movement passes serialize rather than drop: each carries its own live fix and each has to
        // re-centre the trigger, so dropping one strands it.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastApiFetchLocation() } returns null
        every { store.getCachedConfig() } returns null
        every { store.getRegisteredIds() } returns emptySet()
        val concurrentApiCalls = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)
        coEvery { apiService.fetchGeofences(any()) } coAnswers {
            val n = concurrentApiCalls.incrementAndGet()
            maxObservedConcurrency.updateAndGet { current -> maxOf(current, n) }
            delay(50)
            concurrentApiCalls.decrementAndGet()
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        }
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        coroutineScope {
            launch { repository.handleMovement(latitude = 1.0, longitude = 1.0) }
            launch { repository.handleMovement(latitude = 2.0, longitude = 2.0) }
        }

        maxObservedConcurrency.get() shouldBeEqualTo 1
        coVerify(exactly = 2) { apiService.fetchGeofences(any()) }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun handleMovement_givenRefreshHoldingTheSlot_expectTriggerStillReCentredAtLiveFix() = runTest {
        // Field failure: a trigger EXIT cold-starts the process, init's refresh takes the slot and
        // skips (anchored on the registration center, so it measures zero movement), and the movement
        // pass was dropped — leaving the fired trigger un-recentred, so nothing can fire again.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis()
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        // Cache present, nothing registered: init's pass re-ranks locally, holding the slot.
        every { store.getRegisteredIds() } returns emptySet()
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.replaceGeofences(any(), any()) } coAnswers {
            delay(200.milliseconds)
            Result.success(Unit)
        }
        val initPass = launch { repository.refresh(latitude = 0.0, longitude = 0.0) }
        runCurrent()

        repository.handleMovement(latitude = 0.001, longitude = 0.0)
        initPass.join()

        // Re-centred on the movement fix, not the anchor the init pass was using.
        verify { store.saveLastMovementTriggerLocation(GeofenceLocation(0.001, 0.0)) }
        verify(exactly = 0) { logger.logSyncSkipped(match { it.contains("already in progress") }) }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun handleMovement_givenSlowHolderThatRegistersNothing_expectWaitOutlastsIt() = runTest {
        // The holder can sit in a remote fetch for the HTTP client's whole timeout and then fail,
        // registering nothing — so it never re-centres the fired trigger on its way out. Waiting only
        // as long as a local pass would hand that job back to nobody.
        every { secureUserStore.getUserId() } returns "user-42"
        every { clock.currentTimeMillis() } returns 200_000_000_000L
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getLastSyncTimestamp() } returns 1_000L // stale → the holder goes remote
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { store.getRegisteredIds() } returns emptySet()
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { apiService.fetchGeofences(any()) } coAnswers {
            delay(20.seconds)
            Result.failure(RuntimeException("read timeout"))
        }
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        val holder = launch { repository.refresh(latitude = 0.0, longitude = 0.0) }
        runCurrent()

        repository.handleMovement(latitude = 0.001, longitude = 0.0)
        holder.join()

        verify { store.saveLastMovementTriggerLocation(GeofenceLocation(0.001, 0.0)) }
        verify(exactly = 0) { logger.logSyncSkipped(match { it.contains("already in progress") }) }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun handleMovement_givenHolderOutlastingTheWait_expectSlotLeftFreeForTheNextPass() = runTest {
        // The wait has to end at its bound and leave the slot usable: the flag is process-scoped and
        // nothing else clears it, so a pass that gave up while still holding it would silently drop
        // every later refresh — a worse failure than the stranding the wait exists to prevent.
        every { secureUserStore.getUserId() } returns "user-42"
        every { clock.currentTimeMillis() } returns 200_000_000_000L
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getLastSyncTimestamp() } returns 1_000L
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { store.getRegisteredIds() } returns emptySet()
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { apiService.fetchGeofences(any()) } coAnswers {
            delay(60.seconds) // outlasts the wait, so the movement pass gives up
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        }
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        val holder = launch { repository.refresh(latitude = 0.0, longitude = 0.0) }
        runCurrent()

        repository.handleMovement(latitude = 0.001, longitude = 0.0)
        verify(exactly = 1) { logger.logSyncSkipped(match { it.contains("already in progress") }) }
        holder.join()

        repository.handleMovement(latitude = 0.002, longitude = 0.0)

        // Still one: the second pass took the slot rather than finding it latched.
        verify(exactly = 1) { logger.logSyncSkipped(match { it.contains("already in progress") }) }
        verify { store.saveLastMovementTriggerLocation(GeofenceLocation(0.002, 0.0)) }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun refreshFromLiveFix_givenRefreshHoldingTheSlot_expectRegisteredAroundTheLiveFix() = runTest {
        // Field failure: a cold start's identify refresh took the slot working from the stored
        // anchor, the fix the SDK had asked for arrived 285ms later and was dropped, and the set
        // was registered 2 km from the device. The arming flag is spent on arrival, so nothing
        // requested another fix.
        every { secureUserStore.getUserId() } returns "user-42"
        every { clock.currentTimeMillis() } returns 200_000_000_000L
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getLastSyncTimestamp() } returns 1_000L // stale → both passes go remote
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { store.getRegisteredIds() } returns emptySet()
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { apiService.fetchGeofences(any()) } coAnswers {
            delay(2.seconds)
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        }
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        val identifyPass = launch { repository.refresh(latitude = 0.0, longitude = 0.0) }
        runCurrent()

        // ~2.2 km from the anchor the holder is using, as in the field log.
        repository.refreshFromLiveFix(latitude = 0.02, longitude = 0.0)
        identifyPass.join()

        verify { store.saveLastMovementTriggerLocation(GeofenceLocation(0.02, 0.0)) }
        verify(exactly = 0) { logger.logSyncSkipped(match { it.contains("already in progress") }) }
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
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns
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
        every { distanceFilter.nearest(cached, 50.0, 60.0, any(), any()) } returns cached
        coEvery { manager.replaceGeofencesForBootRestore(any()) } returns Result.success(Unit)

        repository.restoreFromCache()

        // Distance filter receives movementLoc coords, NOT the anchor's.
        verify { distanceFilter.nearest(cached, 50.0, 60.0, any(), any()) }
        verify(exactly = 0) { distanceFilter.nearest(any(), 12.34, 56.78, any(), any()) }
        // Pins the boot-restore manager variant, not the normal one.
        coVerify { manager.replaceGeofencesForBootRestore(any()) }
        coVerify(exactly = 0) { manager.replaceGeofences(any(), any()) }
        coVerify(exactly = 0) { apiService.fetchGeofences(any()) }
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
        every { distanceFilter.nearest(cached, 12.34, 56.78, any(), any()) } returns cached
        coEvery { manager.replaceGeofencesForBootRestore(any()) } returns Result.success(Unit)

        repository.restoreFromCache()

        verify { distanceFilter.nearest(cached, 12.34, 56.78, any(), any()) }
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
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns
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
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns cached
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

    @Test
    fun refresh_givenServerMaxMonitoringDistance_expectForwardedToDistanceFilter() = runTest {
        // The server-configured monitoring cap must reach the distance filter so far geofences
        // are dropped from the registered set.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns null // force a remote fetch
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3, maxMonitoringDistance = 50_000f))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        verify { distanceFilter.nearest(any(), any(), any(), max = 3, maxDistanceMeters = 50_000f) }
    }

    // ---------- initial enter-when-inside (synthesized; GMS INITIAL_TRIGGER_ENTER is unreliable) ----------

    @Test
    fun refreshFromLiveFix_givenNewlyRegisteredFenceDeviceInside_expectInitialEnterEmitted() = runTest {
        // Fresh cache but OS regs wiped → local re-register; the fence is newly registered and the
        // device sits inside it → synthesize the ENTER GMS may have dropped.
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns emptySet() // newly registered
        every { store.getCachedConfig() } returns sampleConfig()
        // Reconcile seeded containment from this fix's geometry; synthesis follows that, not a
        // second geometry pass.
        every { store.getEnteredIds() } returns setOf("biz-1")
        every { distanceFilter.nearest(cached, any(), any(), any(), any()) } returns cached
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refreshFromLiveFix(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 1) {
            transitionEmitter.emit(
                geofenceId = "biz-1",
                transition = Event.GeofenceTransition.ENTER,
                userId = "user-42",
                timestampSeconds = any(),
                geofenceName = any(),
                metadata = any(),
                geosetIds = any(),
                monitorsExit = true
            )
        }
    }

    @Test
    fun handleMovement_givenNewlyRegisteredFenceDeviceInside_expectInitialEnterEmitted() = runTest {
        // The movement trigger fires on the OS's own fix, so a movement pass trusts geometry like
        // refreshFromLiveFix: it must still seed and synthesize, not just rank and prune.
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns emptySet() // newly registered
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getEnteredIds() } returns setOf("biz-1")
        every { distanceFilter.nearest(cached, any(), any(), any(), any()) } returns cached
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.handleMovement(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 1) {
            transitionEmitter.emit(
                geofenceId = "biz-1",
                transition = Event.GeofenceTransition.ENTER,
                userId = "user-42",
                timestampSeconds = any(),
                geofenceName = any(),
                metadata = any(),
                geosetIds = any(),
                monitorsExit = true
            )
        }
    }

    /**
     * A wipe suppresses synthesis for a fix we requested, because it may describe a stretch we never
     * observed. The movement trigger's fix is the OS's own, produced because the device just moved,
     * so no wipe can have made it stale — and this is the path the backstop exists for, since the
     * INITIAL_TRIGGER_ENTER it defers to is the callback that gets missed.
     */
    @Test
    fun handleMovement_givenOsStateWiped_expectInitialEnterStillEmitted() = runTest {
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { secureUserStore.getUserId() } returns "user-42"
        every { clock.elapsedRealtime() } returns 1_000L
        // Stateful: the pass stamps this key mid-flight, and a fixed stub would keep reporting
        // "wiped" after the write that clears it.
        var storedUptime: Long? = 900_000L // uptime regressed -> rebooted
        every { store.getLastRegistrationUptime() } answers { storedUptime }
        every { store.setLastRegistrationUptime(any()) } answers { storedUptime = firstArg() }
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns emptySet() // newly registered
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getEnteredIds() } returns setOf("biz-1")
        every { distanceFilter.nearest(cached, any(), any(), any(), any()) } returns cached
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.handleMovement(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 1) {
            transitionEmitter.emit(
                geofenceId = "biz-1",
                transition = Event.GeofenceTransition.ENTER,
                userId = "user-42",
                timestampSeconds = any(),
                geofenceName = any(),
                metadata = any(),
                geosetIds = any(),
                monitorsExit = true
            )
        }
    }

    @Test
    fun refreshFromLiveFix_givenNewlyRegisteredEnterOnlyFenceDeviceInside_expectInitialEnterNotLatched() = runTest {
        // The synthesized ENTER must carry the fence's real monitoring shape: latching an enter-only
        // fence would suppress every later arrival, since no EXIT can ever release it.
        val cached = listOf(
            GeofenceRegion("biz-1", 0.0, 0.0, 100f, transitionTypes = listOf(GeofenceTransitionType.ENTER))
        )
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns emptySet()
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getEnteredIds() } returns setOf("biz-1")
        every { distanceFilter.nearest(cached, any(), any(), any(), any()) } returns cached
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refreshFromLiveFix(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 1) {
            transitionEmitter.emit(
                geofenceId = "biz-1",
                transition = Event.GeofenceTransition.ENTER,
                userId = "user-42",
                timestampSeconds = any(),
                geofenceName = any(),
                metadata = any(),
                geosetIds = any(),
                monitorsExit = false
            )
        }
    }

    @Test
    fun refresh_givenExitClaimedWhileRegistering_expectNoSynthesizedEnter() = runTest {
        // The device left during the GMS await, so reconcile did not seed the fence. Synthesizing
        // from this fix's geometry anyway would send an ENTER for a fence we were just told we left,
        // and the phantom guard would drop its genuine EXIT — an ENTER the backend never balances.
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns emptySet() // newly registered
        every { store.getCachedConfig() } returns sampleConfig()
        // Geometry from the fix says inside; containment says otherwise because the exit was claimed.
        every { store.getEnteredIds() } returns emptySet()
        every { distanceFilter.nearest(cached, any(), any(), any(), any()) } returns cached
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 0) { transitionEmitter.emit(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun refresh_givenRebootWipedOsState_expectReRegistrationButNoInitialEnter() = runTest {
        // Uptime regressed → reboot wiped GMS, so everything is re-added (empty existing set). But
        // the launch anchor can predate the reboot, so containment can't be trusted — no synthesis
        // for any fence, registered (biz-1) or never-registered (biz-2), mirroring restoreFromCache.
        val cached = listOf(
            GeofenceRegion("biz-1", 0.0, 0.0, 100f),
            GeofenceRegion("biz-2", 0.0, 0.0, 100f)
        )
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns setOf("biz-1")
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getLastRegistrationUptime() } returns 500_000L
        every { clock.elapsedRealtime() } returns 1_000L // below last registration uptime → rebooted
        every { distanceFilter.nearest(cached, any(), any(), any(), any()) } returns cached
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify { manager.replaceGeofences(any(), emptySet()) }
        coVerify(exactly = 0) { transitionEmitter.emit(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun refresh_givenRebootAndStaleCache_expectRemoteFetchButNoInitialEnter() = runTest {
        // Reboot + expired cache → remote re-fetch, still anchored at the untrusted persisted
        // point — a newly fetched fence containing it must not synthesize either.
        every { secureUserStore.getUserId() } returns "user-42"
        every { clock.currentTimeMillis() } returns 200_000_000_000L
        every { store.getLastSyncTimestamp() } returns 1_000L // stale → remote fetch
        every { store.getCachedRegions() } returns emptyList()
        every { store.getRegisteredIds() } returns emptySet()
        every { store.getLastRegistrationUptime() } returns 500_000L
        every { clock.elapsedRealtime() } returns 1_000L // rebooted
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3)) // g-1 at (0,0) radius 100
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } answers { firstArg() }
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 1) { apiService.fetchGeofences(any()) }
        coVerify(exactly = 0) { transitionEmitter.emit(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun refresh_givenAppUpdateAndStaleCache_expectRemoteFetchButNoInitialEnter() = runTest {
        // App update wiped OS state (package updateTime changed, no reboot). The launch anchor can
        // predate the update just like a reboot, so containment can't be trusted — a newly fetched
        // fence containing it must not synthesize either.
        every { secureUserStore.getUserId() } returns "user-42"
        every { clock.currentTimeMillis() } returns 200_000_000_000L
        every { store.getLastSyncTimestamp() } returns 1_000L // stale → remote fetch
        every { store.getCachedRegions() } returns emptyList()
        every { store.getRegisteredIds() } returns setOf("biz-1")
        every { store.getLastRegistrationUptime() } returns 500L
        every { clock.elapsedRealtime() } returns 1_000L // no reboot (uptime advanced)
        every { store.getLastRegistrationPackageUpdateTime() } returns 1_000L
        every { packageInfo.lastUpdateTimeMs() } returns 2_000L // updated since → app-update wipe
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3)) // g-1 at (0,0) radius 100
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } answers { firstArg() }
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 1) { apiService.fetchGeofences(any()) }
        coVerify(exactly = 0) { transitionEmitter.emit(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun refreshFromLiveFix_givenRegisteredFenceParamsChangedDeviceInside_expectInitialEnter() = runTest {
        // Server grew g-1's radius (50 → 100 m): the fence is re-added to GMS, so it synthesizes
        // like a new fence when the device is inside the new geometry.
        every { secureUserStore.getUserId() } returns "user-42"
        every { clock.currentTimeMillis() } returns 200_000_000_000L
        every { store.getLastSyncTimestamp() } returns 1_000L // stale → remote fetch
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("g-1", 0.0, 0.0, 50f))
        every { store.getRegisteredIds() } returns setOf("g-1")
        every { store.getEnteredIds() } returns setOf("g-1")
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3)) // g-1 at (0,0) radius 100
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } answers { firstArg() }
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refreshFromLiveFix(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 1) {
            transitionEmitter.emit(
                geofenceId = "g-1",
                transition = Event.GeofenceTransition.ENTER,
                userId = "user-42",
                timestampSeconds = any(),
                geofenceName = any(),
                metadata = any(),
                geosetIds = any(),
                monitorsExit = any()
            )
        }
    }

    @Test
    fun refresh_givenRegisteredFenceParamsChangedAnchorInside_expectNoInitialEnter() = runTest {
        // Same geometry edit, but the pass runs off the anchor. The record was carried forward from
        // an older visit and the anchor still reads "inside", so synthesis would fire an arrival on
        // two pieces of stale evidence — days after the device may have left.
        every { secureUserStore.getUserId() } returns "user-42"
        every { clock.currentTimeMillis() } returns 200_000_000_000L
        every { store.getLastSyncTimestamp() } returns 1_000L // stale → remote fetch
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("g-1", 0.0, 0.0, 50f))
        every { store.getRegisteredIds() } returns setOf("g-1")
        every { store.getEnteredIds() } returns setOf("g-1")
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3)) // g-1 at (0,0) radius 100
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } answers { firstArg() }
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 0) { transitionEmitter.emit(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun refreshFromLiveFix_givenFenceMovedAwayFromDevice_expectContainmentAndMarkReset() = runTest {
        // g-1's circle moved while the device was recorded inside the old one. GMS promises no EXIT
        // for a circle it no longer holds, so carrying the record and the reported-ENTER mark forward
        // would drop the first genuine arrival at the new circle and deliver its EXIT unmatched.
        every { secureUserStore.getUserId() } returns "user-42"
        every { clock.currentTimeMillis() } returns 200_000_000_000L
        every { store.getLastSyncTimestamp() } returns 1_000L // stale → remote fetch
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("g-1", 0.0, 0.0, 50f))
        every { store.getRegisteredIds() } returns setOf("g-1")
        every { store.getEnteredIds() } returns setOf("g-1")
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3)) // g-1 at (0,0) radius 100
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } answers { firstArg() }
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)
        every { store.reconcileEnteredIds(any(), any(), any(), any()) } returns setOf("g-1")

        // Device ~1.1 km away: outside the new circle, so the old record cannot be trusted.
        repository.refreshFromLiveFix(latitude = 0.01, longitude = 0.0)

        verify { store.reconcileEnteredIds(any(), any(), any(), setOf("g-1")) }
        // Mark dropped, so the arrival at the new circle is not mistaken for a repeat.
        verify { store.pruneEmittedEnterIds(match { "g-1" !in it }) }
    }

    @Test
    fun refreshFromLiveFix_givenOsStateWiped_expectNoInitialEnter() = runTest {
        // A reboot or app update re-registers every fence with INITIAL_TRIGGER_ENTER, so GMS reports
        // the arrival itself. Synthesizing as well only adds a second source of truth at the moment
        // the stored record is least likely to describe where the device actually is.
        every { secureUserStore.getUserId() } returns "user-42"
        every { clock.currentTimeMillis() } returns 200_000_000_000L
        every { clock.elapsedRealtime() } returns 1_000L
        // Stateful, because the pass stamps this key mid-flight: a fixed stub would keep reporting
        // "wiped" after the write that clears it and pass whether or not the guard reads it in time.
        var storedUptime: Long? = 900_000L // uptime regressed -> rebooted
        every { store.getLastRegistrationUptime() } answers { storedUptime }
        every { store.setLastRegistrationUptime(any()) } answers { storedUptime = firstArg() }
        every { store.getLastSyncTimestamp() } returns 1_000L
        every { store.getRegisteredIds() } returns emptySet()
        every { store.getEnteredIds() } returns setOf("g-1")
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } answers { firstArg() }
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refreshFromLiveFix(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 0) { transitionEmitter.emit(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun refresh_givenFenceMovedAwayFromAnchor_expectContainmentAndMarkReset() = runTest {
        // Same moved circle, judged off the anchor. The record and mark describe a circle GMS no
        // longer holds, so keeping them swallows the first arrival at the new one — GMS's own
        // report, not the synthesis backstop an anchor pass gives up. Retiring them seeds nothing.
        every { secureUserStore.getUserId() } returns "user-42"
        every { clock.currentTimeMillis() } returns 200_000_000_000L
        every { store.getLastSyncTimestamp() } returns 1_000L
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("g-1", 0.0, 0.0, 50f))
        every { store.getRegisteredIds() } returns setOf("g-1")
        every { store.getEnteredIds() } returns setOf("g-1")
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } answers { firstArg() }
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)
        every { store.reconcileEnteredIds(any(), any(), any(), any()) } returns setOf("g-1")

        repository.refresh(latitude = 0.01, longitude = 0.0)

        verify {
            store.reconcileEnteredIds(
                registeredIds = any(),
                inside = null,
                sinceEpoch = any(),
                resetIds = setOf("g-1")
            )
        }
        verify { store.pruneEmittedEnterIds(match { "g-1" !in it }) }
    }

    @Test
    fun refreshFromLiveFix_givenArrivalReportedWhileRegistrationAwaited_expectMarkKeptWithRecord() = runTest {
        // Same moved circle, but GMS reported an arrival at it while registration was awaited, so the
        // store keeps the record. The mark has to follow: it belongs to that reported arrival, and
        // clearing it would let the next report through as a fresh one.
        every { secureUserStore.getUserId() } returns "user-42"
        every { clock.currentTimeMillis() } returns 200_000_000_000L
        every { store.getLastSyncTimestamp() } returns 1_000L
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("g-1", 0.0, 0.0, 50f))
        every { store.getRegisteredIds() } returns setOf("g-1")
        every { store.getEnteredIds() } returns setOf("g-1")
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } answers { firstArg() }
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)
        // Store declines the reset because the arrival is newer than the fix that asked for it.
        every { store.reconcileEnteredIds(any(), any(), any(), any()) } returns emptySet()

        repository.refreshFromLiveFix(latitude = 0.01, longitude = 0.0)

        verify { store.reconcileEnteredIds(any(), any(), any(), setOf("g-1")) }
        verify { store.pruneEmittedEnterIds(match { "g-1" in it }) }
    }

    @Test
    fun refresh_givenFenceReRegisteredWhileDeviceInside_expectRecordAndMarkKept() = runTest {
        // Radius grew 50 → 100 m with the device inside throughout. Resetting here would let the
        // synthesized ENTER fire for a visit already reported.
        every { secureUserStore.getUserId() } returns "user-42"
        every { clock.currentTimeMillis() } returns 200_000_000_000L
        every { store.getLastSyncTimestamp() } returns 1_000L
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("g-1", 0.0, 0.0, 50f))
        every { store.getRegisteredIds() } returns setOf("g-1")
        every { store.getEnteredIds() } returns setOf("g-1")
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } answers { firstArg() }
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        verify { store.reconcileEnteredIds(any(), any(), any(), emptySet()) }
        verify { store.pruneEmittedEnterIds(match { "g-1" in it }) }
    }

    @Test
    fun refresh_givenStaleContainmentRecordAndDeviceOutside_expectNoInitialEnter() = runTest {
        // reconcile carries containment forward for fences that stay registered, so a param-drift
        // re-add can find a record that outlived the visit. Geometry has to agree before we
        // synthesize, or we report an arrival somewhere the device left long ago.
        every { secureUserStore.getUserId() } returns "user-42"
        every { clock.currentTimeMillis() } returns 200_000_000_000L
        every { store.getLastSyncTimestamp() } returns 1_000L // stale → remote fetch
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("g-1", 0.0, 0.0, 50f))
        every { store.getRegisteredIds() } returns setOf("g-1")
        every { store.getEnteredIds() } returns setOf("g-1") // stale: carried forward, not current
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3)) // g-1 at (0,0) radius 100
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } answers { firstArg() }
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        // ~1.1 km north of g-1, far outside its 100 m radius.
        repository.refresh(latitude = 0.01, longitude = 0.0)

        coVerify(exactly = 0) {
            transitionEmitter.emit(
                geofenceId = "g-1",
                transition = Event.GeofenceTransition.ENTER,
                userId = any(),
                timestampSeconds = any(),
                geofenceName = any(),
                metadata = any(),
                geosetIds = any(),
                monitorsExit = any()
            )
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun refresh_givenUserChangesDuringRegistration_expectNoInitialEnter() = runTest {
        // clearIdentify rewrites the user store without taking stateMutex, so identity can change
        // while the GMS call is awaited; synthesis must recheck before queueing a delivery row.
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        val gmsGate = CompletableDeferred<Unit>()
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns emptySet()
        every { store.getCachedConfig() } returns sampleConfig()
        every { distanceFilter.nearest(cached, any(), any(), any(), any()) } returns cached
        coEvery { manager.replaceGeofences(any(), any()) } coAnswers {
            gmsGate.await()
            Result.success(Unit)
        }

        val refreshJob = launch { repository.refresh(latitude = 0.0, longitude = 0.0) }
        runCurrent() // pre-register identity check passed; now suspended in the GMS call
        every { secureUserStore.getUserId() } returns null // signed out mid-await
        gmsGate.complete(Unit)
        refreshJob.join()

        coVerify(exactly = 0) { transitionEmitter.emit(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun refresh_givenAlreadyRegisteredFenceDeviceInside_expectNoInitialEnter() = runTest {
        // Device is inside, but the fence was already monitored (regs intact, no reboot) → no
        // re-emit; only a genuinely-new registration synthesizes an enter.
        val cached = listOf(GeofenceRegion("biz-1", 0.02, 0.0, 300f))
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getLastMovementTriggerLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns setOf("biz-1") // already registered, regs intact
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns cached
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        // ~2.2 km move → local re-rank runs, and the device is inside biz-1 (radius 300 m at 0.02,0.0).
        repository.refresh(latitude = 0.02, longitude = 0.0)

        coVerify(exactly = 0) { transitionEmitter.emit(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun refresh_givenNewlyRegisteredFenceDeviceOutside_expectNoInitialEnter() = runTest {
        // Newly registered but the device isn't within the fence radius → no synthesized enter.
        // Fence ~111 km from the (0,0) anchor; local re-register (fresh cache, regs wiped).
        val cached = listOf(GeofenceRegion("biz-1", 1.0, 0.0, 100f))
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns emptySet()
        every { store.getCachedConfig() } returns sampleConfig()
        every { distanceFilter.nearest(cached, any(), any(), any(), any()) } returns cached
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 0) { transitionEmitter.emit(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun refresh_givenExitOnlyFenceDeviceInside_expectNoInitialEnter() = runTest {
        // A fence that doesn't monitor ENTER gets no synthesized enter, even sitting inside it.
        val cached = listOf(
            GeofenceRegion("biz-1", 0.0, 0.0, 100f, transitionTypes = listOf(GeofenceTransitionType.EXIT))
        )
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns emptySet()
        every { store.getCachedConfig() } returns sampleConfig()
        every { distanceFilter.nearest(cached, any(), any(), any(), any()) } returns cached
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 0) { transitionEmitter.emit(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun refresh_givenRegistrationFails_expectNoInitialEnter() = runTest {
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns emptySet()
        every { store.getCachedConfig() } returns sampleConfig()
        every { distanceFilter.nearest(cached, any(), any(), any(), any()) } returns cached
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.failure(RuntimeException("gms boom"))

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 0) { transitionEmitter.emit(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun restoreFromCache_givenDeviceInside_expectNoInitialEnter() = runTest {
        // Boot restore never synthesizes an enter — its anchor may be stale after the device moved
        // while off, so containment can't be trusted (matches iOS).
        val movementLoc = GeofenceLocation(latitude = 50.0, longitude = 60.0)
        val cached = listOf(GeofenceRegion("biz-1", 50.0, 60.0, 100f))
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastMovementTriggerLocation() } returns movementLoc
        every { store.getLastApiFetchLocation() } returns null
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns emptySet()
        every { distanceFilter.nearest(cached, 50.0, 60.0, any(), any()) } returns cached
        coEvery { manager.replaceGeofencesForBootRestore(any()) } returns Result.success(Unit)

        repository.restoreFromCache()

        coVerify(exactly = 0) { transitionEmitter.emit(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    // ---------- live fix arriving behind an anchor pass ----------

    /**
     * Wires the keys an anchor pass writes and the pass behind it reads back, so a live fix that
     * follows one takes SKIP for the real reason rather than a stubbed one.
     */
    private fun statefulStore(
        cached: List<GeofenceRegion>,
        preRegistered: Set<String> = emptySet()
    ): List<Set<String>?> {
        val reconciledInside = mutableListOf<Set<String>?>()
        var registeredIds = preRegistered
        // Null models key-absence, which is what `hasContainmentRecord` reads.
        var enteredIds: Set<String>? = null
        // Stand in for the registering pass that [preRegistered] represents.
        var registrationUptime: Long? = 10_000L.takeIf { preRegistered.isNotEmpty() }
        var movementLocation: GeofenceLocation? = GeofenceLocation(0.0, 0.0).takeIf { preRegistered.isNotEmpty() }
        every { secureUserStore.getUserId() } returns "user-42"
        every { clock.elapsedRealtime() } returns 10_000L
        every { store.getCachedRegions() } returns cached
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L
        every { store.getRegisteredIds() } answers { registeredIds }
        every { store.saveRegisteredIds(any()) } answers { registeredIds = firstArg() }
        every { store.getLastRegistrationUptime() } answers { registrationUptime }
        every { store.setLastRegistrationUptime(any()) } answers { registrationUptime = firstArg() }
        every { store.getLastMovementTriggerLocation() } answers { movementLocation }
        every { store.saveLastMovementTriggerLocation(any()) } answers { movementLocation = firstArg() }
        every { store.getEnteredIds() } answers { enteredIds.orEmpty() }
        every { store.claimExit(any()) } answers {
            val id = firstArg<String>()
            val wasInside = id in enteredIds.orEmpty()
            enteredIds = enteredIds?.minus(id)
            wasInside
        }
        every { store.reconcileEnteredIds(any(), any(), any(), any()) } answers {
            val registered = firstArg<Set<String>>()
            val inside = secondArg<Set<String>?>()
            reconciledInside += inside
            enteredIds = if (inside == null) {
                enteredIds?.intersect(registered)
            } else {
                enteredIds.orEmpty().intersect(registered) + inside
            }
            emptySet()
        }
        every { distanceFilter.nearest(cached, any(), any(), any(), any()) } returns cached
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)
        return reconciledInside
    }

    @Test
    fun refreshFromLiveFix_givenAnchorPassMadeInputsFresh_expectContainmentJudged() = runTest {
        // Cold start registers from the anchor, which stamps every freshness input, so the live fix
        // behind it takes SKIP. It still owns the reseed: otherwise nothing ever judges containment
        // and the receiver reads the eventual genuine EXIT as unmatched.
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        statefulStore(cached)

        repository.refresh(latitude = 0.0, longitude = 0.0)
        store.getEnteredIds().shouldBeEmpty()

        repository.refreshFromLiveFix(latitude = 0.0, longitude = 0.0)

        verify { logger.logSyncSkippedFresh() }
        store.getEnteredIds() shouldContainSame setOf("biz-1")
    }

    @Test
    fun refreshFromLiveFix_givenRecordClearedByAnExit_expectReseedButNoSynthesizedEnter() = runTest {
        // The chain this pass must not open: a real EXIT clears the record and the mark, then a
        // foreground fix reading a hair inside re-seeds. Synthesizing there would fabricate an
        // arrival and re-arm the mark, which would then swallow the user's real next one.
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        statefulStore(cached)

        repository.refresh(latitude = 0.0, longitude = 0.0)
        repository.refreshFromLiveFix(latitude = 0.0, longitude = 0.0)
        // The genuine departure clears the record, as the receiver's EXIT path does.
        store.claimExit("biz-1") shouldBeEqualTo true
        store.getEnteredIds().shouldBeEmpty()
        repository.refreshFromLiveFix(latitude = 0.0, longitude = 0.0)

        store.getEnteredIds() shouldContainSame setOf("biz-1")
        coVerify(exactly = 0) { transitionEmitter.emit(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun refreshFromLiveFix_givenFreshInputsAndDeviceOutside_expectNoEnterAndRecordWritten() = runTest {
        // The record is written even with nothing inside: that write is what lets the EXIT guard
        // stop deferring, and geometry that puts the device outside must not invent an arrival.
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        val reconciledInside = statefulStore(cached)

        repository.refresh(latitude = 0.0, longitude = 0.0)
        // ~555m: outside the 100m fence, inside both refresh radii, so the pass still takes SKIP.
        repository.refreshFromLiveFix(latitude = 0.005, longitude = 0.0)

        // Null from the anchor's registering pass, then the live fix's own empty judgement.
        reconciledInside shouldBeEqualTo listOf(null, emptySet())
        coVerify(exactly = 0) { transitionEmitter.emit(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun refreshFromLiveFix_givenCachedFenceNotRegistered_expectItLeftOutOfTheJudgement() = runTest {
        // The cache holds more than the OS monitors. Recording a visit to a fence GMS never got
        // would report an arrival nothing can ever balance with an EXIT.
        val registered = GeofenceRegion("biz-1", 0.0, 0.0, 100f)
        val unregistered = GeofenceRegion("biz-2", 0.0, 0.0, 100f)
        val cached = listOf(registered, unregistered)
        val reconciledInside = statefulStore(cached)
        every { distanceFilter.nearest(cached, any(), any(), any(), any()) } returns listOf(registered)

        repository.refresh(latitude = 0.0, longitude = 0.0)
        repository.refreshFromLiveFix(latitude = 0.0, longitude = 0.0)

        reconciledInside shouldBeEqualTo listOf(null, setOf("biz-1"))
        store.getEnteredIds() shouldContainSame setOf("biz-1")
    }

    @Test
    fun refreshFromLiveFix_givenUserSignedOutDuringThePass_expectContainmentLeftUnjudged() = runTest {
        // The slot wait and the action decision both release before this writes, so a sign-out can
        // land in between; seeding then would hand the next user the previous one's containment.
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        val reconciledInside = statefulStore(cached, preRegistered = setOf("biz-1"))
        every { secureUserStore.getUserId() } returnsMany listOf("user-42", null)

        repository.refreshFromLiveFix(latitude = 0.0, longitude = 0.0)

        verify { logger.logSyncSkippedFresh() }
        reconciledInside.shouldBeEmpty()
        store.getEnteredIds().shouldBeEmpty()
    }

    @Test
    fun refresh_givenAnchorPassOnFreshInputs_expectContainmentLeftUnjudged() = runTest {
        // An anchor describes where the device used to be. A SKIP on one must judge nothing — the
        // absent record is what keeps the EXIT guard deferring rather than dropping a real departure.
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        val reconciledInside = statefulStore(cached)

        repository.refresh(latitude = 0.0, longitude = 0.0)
        repository.refresh(latitude = 0.0, longitude = 0.0)

        verify(exactly = 1) { logger.logSyncSkippedFresh() }
        reconciledInside shouldBeEqualTo listOf(null)
        store.getEnteredIds().shouldBeEmpty()
    }

    /** Latitude offset from the equator covering roughly [meters], for placing a fence at a known distance. */
    private fun metersOfLatitude(meters: Int): Double = meters / 111_320.0

    private fun sampleConfig(
        remoteFetchRefreshExpiry: Long = 86_400_000L,
        maxMonitoringDistance: Float = GeofenceConstants.NO_MONITORING_DISTANCE_CAP_METERS
    ): GeofenceConfig = GeofenceConfig(
        localRefreshTriggerRadius = 1_000f,
        remoteFetchRefreshTriggerRadius = 5_000f,
        remoteFetchRefreshExpiry = remoteFetchRefreshExpiry,
        duplicateEventsExpiry = 3_600_000L,
        maxBusinessGeofences = 19,
        maxMonitoringDistance = maxMonitoringDistance
    )

    // Config block but no geofences — the "account has zero geofences" case, distinct from
    // "geofences exist but all are beyond the monitoring distance".
    private fun emptyResponse(maxBusinessGeofences: Int = 3): GeofenceApiResponse {
        val json = """
            {
              "config": {
                "local_refresh_trigger_radius": 1000,
                "remote_fetch_refresh_trigger_radius": 5000,
                "remote_fetch_refresh_expiry_time": 86400000,
                "duplicate_events_expiry_time": 3600000,
                "android": { "max_business_geofence": $maxBusinessGeofences }
              },
              "geofences": []
            }
        """.trimIndent()
        return jsonSerializer.decode(GeofenceApiResponse.serializer(), json)
    }

    private fun sampleResponse(
        maxBusinessGeofences: Int,
        localRefreshTriggerRadius: Float = 1000f,
        maxMonitoringDistance: Float? = null
    ): GeofenceApiResponse {
        val maxMonitoringDistanceLine =
            maxMonitoringDistance?.let { "\"max_monitoring_distance\": $it," } ?: ""
        val json = """
            {
              "config": {
                "local_refresh_trigger_radius": $localRefreshTriggerRadius,
                "remote_fetch_refresh_trigger_radius": 5000,
                "remote_fetch_refresh_expiry_time": 86400000,
                "duplicate_events_expiry_time": 3600000,
                $maxMonitoringDistanceLine
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
