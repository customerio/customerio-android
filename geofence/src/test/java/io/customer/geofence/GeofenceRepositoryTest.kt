package io.customer.geofence

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.api.GeofenceApiResponse
import io.customer.geofence.api.GeofenceApiService
import io.customer.geofence.api.toDomainRegions
import io.customer.geofence.polygon.PolygonCoordinate
import io.customer.geofence.polygon.PolygonGeofenceServiceController
import io.customer.geofence.polygon.PolygonSupport
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
import org.amshove.kluent.shouldNotContain
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
    private val polygonController: PolygonGeofenceServiceController = mockk(relaxed = true)
    private val jsonSerializer = GeofenceJsonSerializer()

    private lateinit var repository: GeofenceRepositoryImpl

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { })
        every { clock.currentTimeMillis() } answers { System.currentTimeMillis() }
        // The relaxed mock's false means "an identify landed mid-pass".
        every { store.saveRoutableRegisteredIdsIfCurrent(any(), any()) } returns true
        // The relaxed mock's 0f would re-rank every pass; null means no radius recorded.
        every { store.getLastMovementTriggerRadius() } returns null
        every { store.getRoutableRegisteredIds() } answers { store.getRegisteredIds() }
        every { polygonController.clearUserScopedState() } answers {
            store.clearUserScopedState()
        }
        every { polygonController.clearUserSessionRetainingOsRegistrations() } answers {
            store.clearUserSessionRetainingOsRegistrations()
        }
        every { polygonController.completeUserReset(any(), any()) } answers {
            if (secondArg()) store.clearUserScopedState() else store.clearUserSessionRetainingOsRegistrations()
        }
        every { polygonController.publishRegistrationIfCurrent(any(), any(), any()) } answers {
            thirdArg<() -> Unit>().invoke()
            true
        }
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
        logger = logger,
        polygonController = polygonController,
        // Matches the production graph; otherwise polygon tests take the fail-closed path.
        polygonSupport = PolygonSupport.Enabled
    )

    @Test
    fun refresh_givenRecentSuccessfulSyncAndNotForced_expectSkipApiCall() = runTest {
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
        // Catches a movement EXIT missed while the app was dead.
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

        coVerify { apiService.fetchGeofences(GeofenceLocation(37.7749, -122.4194)) }
    }

    @Test
    fun refresh_givenFreshButMovedBeyondFetchRadius_expectRemoteFetchWithLocation() = runTest {
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

        // 1° latitude ≈ 111 km, beyond the 5 km fetch radius.
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
        verify { store.saveLastMovementTriggerLocation(GeofenceLocation(1.0, 0.0), any()) }
        verify { logger.logMovementRearmedAfterFailedRefresh() }
        // Not a successful sync, so the next EXIT still fetches remotely.
        verify(exactly = 0) { store.saveApiFetchStateIfCurrent(any(), any(), any()) }
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
        // Ranking staleness is measured from the last registration, not the fetch anchor.
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
    fun refreshFromLiveFix_givenDeviceOutsideTheShrunkTrigger_expectLocalRerankNotSkip() = runTest {
        // A polygon shrank the trigger to 150 m. At 300 m out the device has left the circle the OS
        // holds, though it is inside the configured 1 km.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getLastMovementTriggerLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getLastMovementTriggerRadius() } returns 150f
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { store.getRegisteredIds() } returns setOf("biz-1")
        every { store.getRoutableRegisteredIds() } returns setOf("biz-1")
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refreshFromLiveFix(latitude = metersOfLatitude(300), longitude = 0.0)

        coVerify(exactly = 0) { apiService.fetchGeofences(any()) }
        coVerify { manager.replaceGeofences(any(), any()) }
        verify(exactly = 0) { logger.logSyncSkippedFresh() }
    }

    @Test
    fun refreshFromLiveFix_givenNoRecordedRadius_expectConfiguredRadiusStillGoverns() = runTest {
        // Upgraded installs have a stored centre but no stored radius.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getLastMovementTriggerLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getLastMovementTriggerRadius() } returns null
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { store.getRegisteredIds() } returns setOf("biz-1")
        every { store.getRoutableRegisteredIds() } returns setOf("biz-1")

        repository.refreshFromLiveFix(latitude = metersOfLatitude(300), longitude = 0.0)

        coVerify(exactly = 0) { manager.replaceGeofences(any(), any()) }
        verify { logger.logSyncSkippedFresh() }
    }

    @Test
    fun refresh_givenStaleLastSync_expectApiCalled() = runTest {
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
        // 72h server expiry, synced 25h ago: skips, where the 24h default would fetch.
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
        // 1h server expiry, synced 2h ago: fetches, where the 24h default would skip.
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
        // Armed, so the failed pass doesn't re-rank from the cache.
        every { store.getRoutableRegisteredIds() } returns setOf("biz-1")
        coEvery { apiService.fetchGeofences(any()) } returns Result.failure(error)

        val result = repository.refresh(latitude = 12.34, longitude = 56.78)

        result.isFailure shouldBeEqualTo true
        result.exceptionOrNull() shouldBeEqualTo error
        verify { logger.logApiFetchFailed(match { it?.contains("network down") == true }) }
        verify(exactly = 0) { store.saveRegisteredIds(any()) }
        verify(exactly = 0) { store.saveApiFetchStateIfCurrent(any(), any(), any()) }
        coVerify(exactly = 0) { manager.replaceGeofences(any(), any()) }
    }

    @Test
    fun refresh_givenResponseMappingThrows_expectTheFetchStillCatalogued() = runTest {
        // The catalog runs before mapping, so a response the mapper rejects is still described.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns null
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        mockkStatic("io.customer.geofence.api.GeofenceApiResponseKt")
        try {
            every { any<GeofenceApiResponse>().toDomainRegions(any()) } throws IllegalStateException("mapper defect")

            repository.refresh(latitude = 12.34, longitude = 56.78).isFailure shouldBeEqualTo true

            // Captured: the lambda seen in a verify block is mockk's stand-in and throws if invoked.
            val catalog = slot<() -> List<GeofenceCatalogEntry>>()
            verify {
                logger.logApiFetchResult(
                    returnedCount = any(),
                    elapsedMillis = any(),
                    catalog = capture(catalog)
                )
            }
            catalog.captured().isNotEmpty() shouldBeEqualTo true
        } finally {
            unmockkStatic("io.customer.geofence.api.GeofenceApiResponseKt")
        }
    }

    @Test
    fun refresh_givenResponseMappingThrows_expectFailureNotEmptySuccess() = runTest {
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns null
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        mockkStatic("io.customer.geofence.api.GeofenceApiResponseKt")
        try {
            every { any<GeofenceApiResponse>().toDomainRegions(any()) } throws IllegalStateException("mapper defect")

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
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)
        val capturedLoc = slot<GeofenceLocation>()
        every { store.saveLastMovementTriggerLocation(capture(capturedLoc), any()) } returns Unit

        repository.refresh(latitude = 12.34, longitude = 56.78)

        capturedLoc.captured shouldBeEqualTo GeofenceLocation(latitude = 12.34, longitude = 56.78)
    }

    @Test
    fun refresh_givenKillSwitchConfig_expectNothingRegisteredAndMovementTriggerLocationCleared() = runTest {
        // maxBusinessGeofences = 0 is the server kill switch.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(emptyResponse(maxBusinessGeofences = 0))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns emptyList()
        val captured = slot<List<GeofenceRegion>>()
        coEvery { manager.replaceGeofences(capture(captured), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 12.34, longitude = 56.78)

        captured.captured.shouldBeEmpty()
        verify(exactly = 0) { store.saveLastMovementTriggerLocation(any(), any()) }
        verify { store.clearLastMovementTriggerLocation() }
        verify { logger.logSyncSucceeded(0, movementTriggerRegistered = false, elapsedMillis = any()) }
        verify(exactly = 0) { logger.logMovementTriggerRegistered(any(), any(), any()) }
    }

    @Test
    fun refresh_givenEmptyResponseWithPositiveBudget_expectMovementTriggerKept() = runTest {
        // `/nearest` is distance-capped, so empty means "none nearby", not "feature off".
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(emptyResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns emptyList()
        val captured = slot<List<GeofenceRegion>>()
        coEvery { manager.replaceGeofences(capture(captured), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 12.34, longitude = 56.78)

        captured.captured.map { it.id } shouldBeEqualTo listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID)
        verify { store.saveLastMovementTriggerLocation(GeofenceLocation(12.34, 56.78), any()) }
        verify(exactly = 0) { store.clearLastMovementTriggerLocation() }
    }

    @Test
    fun refresh_givenGeofencesExistButAllBeyondCap_expectMovementTriggerRegisteredAndLocationSaved() = runTest {
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3)) // non-empty fetched set
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns emptyList() // none near
        val captured = slot<List<GeofenceRegion>>()
        coEvery { manager.replaceGeofences(capture(captured), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 12.34, longitude = 56.78)

        captured.captured.map { it.id } shouldBeEqualTo listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID)
        verify { store.saveLastMovementTriggerLocation(GeofenceLocation(12.34, 56.78), any()) }
        verify(exactly = 0) { store.clearLastMovementTriggerLocation() }
        verify { logger.logSyncSucceeded(0, movementTriggerRegistered = true, elapsedMillis = any()) }
    }

    @Test
    fun refresh_givenManagerAddFails_expectMovementTriggerLocationNotPersisted() = runTest {
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.failure(RuntimeException("boom"))

        repository.refresh(latitude = 12.34, longitude = 56.78)

        verify(exactly = 0) { store.saveLastMovementTriggerLocation(any(), any()) }
    }

    @Test
    fun refreshFromLiveFix_givenRegistrationSucceeds_expectContainmentSeededFromGeometryNotFromEnterEvents() = runTest {
        // Synthesis can be suppressed, so without a seed the later genuine EXIT reads as unentered.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        val inside = GeofenceRegion("biz-inside", 0.0, 0.0, 500f)
        val outside = GeofenceRegion("biz-outside", 1.0, 1.0, 100f)
        coEvery { apiService.fetchGeofences(any()) } returns Result.success(sampleResponse(maxBusinessGeofences = 5))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns listOf(inside, outside)
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        val result = repository.refreshFromLiveFix(latitude = 0.0, longitude = 0.0)

        result.isSuccess shouldBeEqualTo true
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
        // No accuracy margin: requiring the error circle to fit makes small fences unjudgable.
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

        verify { store.reconcileEnteredIds(registeredIds = any(), inside = setOf("biz-edge"), sinceEpoch = any()) }
    }

    @Test
    fun refreshFromLiveFix_givenFixJustOutsideAReRegisteredFence_expectMarkRetired() = runTest {
        // No accuracy margin outside either: a kept mark drops the next real arrival as redundant.
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
        // The anchor is where the device once was, so anchor passes only rank and prune.
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
        // A crossing reported while queued postdates the fix, so the epoch is read before the wait.
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
        // An EXIT during the GMS await is newer than the fix, so the epoch is read before it.
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

        verify(exactly = 0) { store.reconcileEnteredIds(any(), any(), any()) }
        verify(exactly = 0) { store.pruneEmittedEnterIds(any()) }
    }

    @Test
    fun refresh_givenRegistrationSucceeds_expectEmittedEnterPrunedToRegisteredSet() = runTest {
        // Otherwise a fence evicted while inside keeps a mark no EXIT will clear.
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
    fun refresh_givenIdentifyWhileAnotherRefreshHoldsTheSlot_expectRoutingArmedForTheNewSession() = runTest {
        // A can only arm its own generation and beginUserSession already cleared routing, so
        // dropping B's refresh as a duplicate would leave routing empty.
        every { secureUserStore.getUserId() } returns "user-b"
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3, localRefreshTriggerRadius = 1500f))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))

        // Generation 7 is A's; B's identify bumps it to 8 while A is still inside replaceGeofences.
        every { store.userStateGeneration() } returns 7L
        val armed = mutableListOf<Long>()
        every { store.saveRoutableRegisteredIdsIfCurrent(any(), capture(armed)) } answers
            { secondArg<Long>() == store.userStateGeneration() }
        val registrationEntered = CompletableDeferred<Unit>()
        val releaseRegistration = CompletableDeferred<Unit>()
        coEvery { manager.replaceGeofences(any(), any()) } coAnswers {
            registrationEntered.complete(Unit)
            releaseRegistration.await()
            Result.success(Unit)
        }

        val passA = launch { repository.refresh(latitude = 1.0, longitude = 2.0) }
        registrationEntered.await()
        every { store.userStateGeneration() } returns 8L
        val passB = launch { repository.refresh(latitude = 1.0, longitude = 2.0) }
        releaseRegistration.complete(Unit)
        passA.join()
        passB.join()

        // Where A's pass is refused isn't pinned, only that 7 is never left armed.
        armed.last() shouldBeEqualTo 8L
        verify { store.saveRoutableRegisteredIdsIfCurrent(any(), 8L) }
        verify(exactly = 0) { logger.logSyncSkipped(match { it.contains("already in progress") }) }
    }

    @Test
    fun refresh_givenSuccessfulRegistration_expectRoutingArmedForTheSameIds() = runTest {
        // beginUserSession writes an empty routable set, so unarmed routing makes the receiver
        // remove every live ID.
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
        // A stamp would make the new user's refresh SKIP and never arm routing.
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
        verify(exactly = 0) { store.saveApiFetchStateIfCurrent(any(), any(), any()) }
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

        verify { store.saveApiFetchStateIfCurrent(any(), any(), 7L) }
    }

    @Test
    fun refresh_givenIdentifyLandsAfterRoutingArmed_expectFreshnessOfferedForThePassGeneration() = runTest {
        // Identify lands between arming and stamping; the pass offers its own generation so the
        // store refuses.
        var generation = 7L
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        every { store.userStateGeneration() } answers { generation }
        every { store.saveRoutableRegisteredIdsIfCurrent(any(), any()) } answers {
            generation = 8L
            true
        }
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3, localRefreshTriggerRadius = 1500f))
        every { distanceFilter.nearest(any(), 12.34, 56.78, 3, any()) } returns
            listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 12.34, longitude = 56.78)

        verify { store.saveApiFetchStateIfCurrent(any(), any(), 7L) }
        verify(exactly = 0) { store.saveApiFetchStateIfCurrent(any(), any(), 8L) }
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
        verify { store.saveApiFetchStateIfCurrent(any(), any(), any()) }
        verify { distanceFilter.nearest(any(), 12.34, 56.78, 3, any()) }
        verify { logger.logSyncSucceeded(filtered.size, movementTriggerRegistered = true, elapsedMillis = any()) }
        verify { store.saveRegisteredIds(captured.captured.map { it.id }.toSet()) }

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
        every { store.saveApiFetchStateIfCurrent(capture(anchorSlot), any(), any()) } returns true

        repository.refresh(latitude = 12.34, longitude = 56.78)

        // Full response, not the filtered set: a local re-rank needs all of it.
        regionsSlot.captured.map { it.id } shouldBeEqualTo listOf("g-1")
        configSlot.captured.localRefreshTriggerRadius shouldBeEqualTo 1500f
        anchorSlot.captured shouldBeEqualTo GeofenceLocation(latitude = 12.34, longitude = 56.78)
    }

    @Test
    fun refresh_givenManagerFails_expectCacheAndAnchorNotPersisted() = runTest {
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
        verify(exactly = 0) { store.saveApiFetchStateIfCurrent(any(), any(), any()) }
    }

    @Test
    fun refresh_givenPreviouslyRegisteredIdsAbsentFromNew_expectStaleRemovedAfterAdd() = runTest {
        // Add runs first so a failed add doesn't wipe the last-known-good registrations.
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
        // Uptime regressed: a reboot wiped GMS, even if BOOT_COMPLETED never fired.
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
        // An app update can cancel the geofence PendingIntent and drop OS registrations.
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
        // Registrations from an SDK that didn't stamp update time: the upgrade was itself an update.
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
        // Unremoved stale IDs stay persisted so the next refresh retries them, else they orphan.
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
        // Two: the stale fence whose removal failed is still monitored.
        verify { logger.logSyncSucceeded(2, movementTriggerRegistered = true, elapsedMillis = any()) }
        persisted.captured shouldContainSame
            setOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "biz-new", "biz-old")
    }

    @Test
    fun refresh_givenStaleRemovalFails_expectDefinitionRetainedAsTombstone() = runTest {
        // The receiver needs this retained definition to tell a retired fence from an uncached one.
        val staleRegion = GeofenceRegion("biz-old", 1.0, 2.0, 150f)
        val newRegion = GeofenceRegion("biz-new", 0.0, 0.0, 100f)
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns setOf("biz-old")
        every { store.getCachedRegions() } returns listOf(staleRegion)
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns listOf(newRegion)
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)
        coEvery { manager.removeGeofencesByIds(any()) } returns
            Result.failure(RuntimeException("remove boom"))
        val retained = slot<List<GeofenceRegion>>()
        every { store.saveRetainedRegisteredRegions(capture(retained)) } returns Unit

        repository.refresh(latitude = 0.0, longitude = 0.0)

        retained.captured shouldBeEqualTo listOf(staleRegion)
    }

    @Test
    fun refresh_givenStaleRemovalFailsRepeatedly_expectTombstoneSurvivesTheCatalogDroppingIt() = runTest {
        // The next sync saves a catalog without the fence, so rebuilding from the catalog alone
        // would lose the tombstone on the first retry.
        val staleRegion = GeofenceRegion("biz-old", 1.0, 2.0, 150f)
        val newRegion = GeofenceRegion("biz-new", 0.0, 0.0, 100f)
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns setOf("biz-old")
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns listOf(newRegion)
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)
        coEvery { manager.removeGeofencesByIds(any()) } returns
            Result.failure(RuntimeException("remove boom"))
        val retained = mutableListOf<List<GeofenceRegion>>()
        every { store.saveRetainedRegisteredRegions(capture(retained)) } returns Unit

        // Pass 1: the catalog still holds the deleted fence.
        every { store.getCachedRegions() } returns listOf(staleRegion)
        every { store.getRetainedRegisteredRegions() } returns emptyList()
        repository.refresh(latitude = 0.0, longitude = 0.0)

        // Pass 2: only the tombstone from pass 1 is left.
        every { store.getCachedRegions() } returns emptyList()
        every { store.getRetainedRegisteredRegions() } returns listOf(staleRegion)
        repository.refresh(latitude = 0.0, longitude = 0.0)

        retained.first() shouldBeEqualTo listOf(staleRegion)
        retained.last() shouldBeEqualTo listOf(staleRegion)
    }

    @Test
    fun refresh_givenStaleRemovalSucceeds_expectTombstonesCleared() = runTest {
        // A kept tombstone would suppress an id a later sync may register again.
        val newRegion = GeofenceRegion("biz-new", 0.0, 0.0, 100f)
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns setOf("biz-old")
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("biz-old", 1.0, 2.0, 150f))
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns listOf(newRegion)
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)
        coEvery { manager.removeGeofencesByIds(any()) } returns Result.success(Unit)
        val retained = slot<List<GeofenceRegion>>()
        every { store.saveRetainedRegisteredRegions(capture(retained)) } returns Unit

        repository.refresh(latitude = 0.0, longitude = 0.0)

        retained.captured.shouldBeEmpty()
    }

    @Test
    fun refresh_givenAllPreviousAbsentFromNew_expectBusinessRemovedButTriggerKept() = runTest {
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
        verify(exactly = 0) { store.saveApiFetchStateIfCurrent(any(), any(), any()) }
        verify(exactly = 0) { logger.logSyncSucceeded(any(), any(), any()) }
    }

    @Test
    fun refresh_givenSecondCallWhileFirstInFlight_expectSecondDroppedAndOnlyOneApiCall() = runTest {
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
    fun refresh_givenMovementHoldingTheSlotForSameSession_expectDroppedWithoutWaiting() = runTest {
        // Without the generation stamp the slot reads as NO_SESSION and this waits out the bound.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns emptySet()
        every { store.userStateGeneration() } returns 7L
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns emptyList()
        val registrationEntered = CompletableDeferred<Unit>()
        val releaseRegistration = CompletableDeferred<Unit>()
        coEvery { manager.replaceGeofences(any(), any()) } coAnswers {
            registrationEntered.complete(Unit)
            releaseRegistration.await()
            Result.success(Unit)
        }

        val movement = launch { repository.handleMovement(latitude = 1.0, longitude = 2.0) }
        registrationEntered.await()
        repository.refresh(latitude = 3.0, longitude = 4.0)
        releaseRegistration.complete(Unit)
        movement.join()

        verify { logger.logSyncSkipped("refresh already in progress") }
        verify(exactly = 0) { logger.logSyncSkipped("refresh already in progress after waiting") }
    }

    @Test
    fun refresh_givenInFlightGateCleared_expectSubsequentCallProceeds() = runTest {
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
        every { secureUserStore.getUserId() } returnsMany listOf("user-A", "user-B")
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))

        val result = repository.refresh(latitude = 0.0, longitude = 0.0)

        result.isSuccess shouldBeEqualTo true
        verify(exactly = 0) { store.saveRegisteredIds(any()) }
        verify(exactly = 0) { store.saveApiFetchStateIfCurrent(any(), any(), any()) }
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
        // A needless re-upsert makes GMS reconcile state and fire spurious transitions.
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
        // Only geometry changes justify a re-register, which fires a spurious initial ENTER.
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
        every { secureUserStore.getUserId() } returns null
        coEvery { manager.clearAll() } returns Result.success(Unit)

        val result = repository.reset()

        result.isSuccess shouldBeEqualTo true
        coVerifyOrder {
            manager.clearAll()
            polygonController.completeUserReset(any(), true)
        }
        verify { cooldownFilter.clearAll() }
        verify(exactly = 0) { store.clearAll() }
    }

    @Test
    fun reset_givenUserSignedInAtResetTime_expectSkipWipe() = runTest {
        // Geofences are workspace-scoped: the signed-in user's identify sync reuses the
        // registrations, and tearing them down would race it.
        every { secureUserStore.getUserId() } returns "user-B"

        val result = repository.reset()

        result.isSuccess shouldBeEqualTo true
        coVerify(exactly = 0) { manager.clearAll() }
        verify(exactly = 0) { store.clearUserScopedState() }
        // Registrations survive, so their cooldown windows must too.
        verify(exactly = 0) { cooldownFilter.clearAll() }
        verify { logger.logResetSuperseded() }
    }

    @Test
    fun reset_givenEmptyCurrentUser_expectWipeProceeds() = runTest {
        // Empty userId is "not identified", matching isUserIdentified.
        every { secureUserStore.getUserId() } returns ""
        coEvery { manager.clearAll() } returns Result.success(Unit)

        val result = repository.reset()

        result.isSuccess shouldBeEqualTo true
        coVerifyOrder {
            manager.clearAll()
            polygonController.completeUserReset(any(), true)
        }
    }

    @Test
    fun reset_givenManagerFails_expectStorePreservedForSelfHeal() = runTest {
        // The next refresh's stale-cleanup diff needs the store to retry the removal.
        every { secureUserStore.getUserId() } returns null
        val error = RuntimeException("gms clear boom")
        coEvery { manager.clearAll() } returns Result.failure(error)

        val result = repository.reset()

        result.isFailure shouldBeEqualTo true
        result.exceptionOrNull() shouldBeEqualTo error
        verify(exactly = 0) { store.clearUserScopedState() }
        verify(exactly = 0) { store.clearAll() }
        verify { logger.logResetFailed("RuntimeException") }
        // Cooldown is user-scoped, so it's wiped even when the OS clear fails.
        verify(exactly = 1) { cooldownFilter.clearAll() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun refresh_givenIdentifyDuringInFlightResetWipe_expectDecisionWaitsAndRefreshes() = runTest {
        // B identifies while reset awaits the GMS clear; deciding on pre-wipe state would SKIP.
        every { secureUserStore.getUserId() } returns null
        var generation = 1L
        var sessionOwner: String? = null
        every { store.userStateGeneration() } answers { generation }
        every { store.activeUserSessionId() } answers { sessionOwner }
        // Pre-wipe state that would SKIP.
        every { store.getLastSyncTimestamp() } answers { clock.currentTimeMillis() }
        every { store.getRegisteredIds() } returns setOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "biz-1")
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("biz-1", 1.0, 2.0, 100f))
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getLastMovementTriggerLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getLastRegistrationUptime() } returns null
        every { polygonController.completeUserReset(any(), any()) } answers {
            every { store.getLastSyncTimestamp() } returns null
            every { store.getRegisteredIds() } returns emptySet()
            every { store.getRoutableRegisteredIds() } returns emptySet()
        }
        val gmsClear = CompletableDeferred<Result<Unit>>()
        coEvery { manager.clearAll() } coAnswers { gmsClear.await() }
        coEvery { apiService.fetchGeofences(any()) } returns Result.failure(IOException("test: fetch reached"))

        val resetJob = launch { repository.reset() }
        runCurrent() // reset holds the state lock, suspended on the GMS clear
        every { secureUserStore.getUserId() } returns "user-B"
        generation = 2L
        sessionOwner = "user-B"
        val refreshJob = launch { repository.refresh(latitude = 0.0, longitude = 0.0) }
        runCurrent() // refresh's decision must now be blocked behind the lock
        gmsClear.complete(Result.success(Unit))
        advanceUntilIdle()
        resetJob.join()
        refreshJob.join()

        verify(exactly = 0) { logger.logSyncSkippedFresh() }
        coVerify(exactly = 1) { apiService.fetchGeofences(any()) }
        verify { polygonController.completeUserReset(1L, true) }
        sessionOwner shouldBeEqualTo "user-B"
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
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getCachedConfig() } returns null
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns emptySet()
        every { distanceFilter.nearest(cached, any(), any(), any(), any()) } returns cached
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        // ~111 m from the anchor, within the fallback 5 km threshold.
        repository.handleMovement(latitude = 0.0, longitude = 0.001)

        coVerify(exactly = 0) { apiService.fetchGeofences(any()) }
        coVerify { manager.replaceGeofences(any(), any()) }
    }

    @Test
    fun handleMovement_givenAnchorWithinThreshold_expectLocalRerankAndNoApiCall() = runTest {
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
        // The fetch radius keeps measuring from the same anchor.
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
        verify(exactly = 0) { store.saveApiFetchStateIfCurrent(any(), any(), any()) }
    }

    @Test
    fun handleMovement_givenSecondCallWhileFirstInFlight_expectSerializedNotDropped() = runTest {
        // Each pass must re-centre the trigger on its own fix, so dropping one strands it.
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
        // Cold start: init's anchor pass takes the slot, and only the movement pass re-centres the
        // fired trigger.
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis()
        every { store.getCachedRegions() } returns listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
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

        verify { store.saveLastMovementTriggerLocation(GeofenceLocation(0.001, 0.0), any()) }
        verify(exactly = 0) { logger.logSyncSkipped(match { it.contains("already in progress") }) }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun handleMovement_givenSlowHolderThatRegistersNothing_expectWaitOutlastsIt() = runTest {
        // A holder can spend the whole HTTP timeout on a fetch and fail without re-centring the trigger.
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

        verify { store.saveLastMovementTriggerLocation(GeofenceLocation(0.001, 0.0), any()) }
        verify(exactly = 0) { logger.logSyncSkipped(match { it.contains("already in progress") }) }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun handleMovement_givenHolderOutlastingTheWait_expectSlotLeftFreeForTheNextPass() = runTest {
        // The flag is process-scoped and nothing else clears it; a latched slot drops every later refresh.
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
        verify { store.saveLastMovementTriggerLocation(GeofenceLocation(0.002, 0.0), any()) }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun refreshFromLiveFix_givenRefreshHoldingTheSlot_expectRegisteredAroundTheLiveFix() = runTest {
        // Nothing requests another fix, so dropping this one leaves the set ranked around the anchor.
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

        // ~2.2 km from the anchor the holder is using.
        repository.refreshFromLiveFix(latitude = 0.02, longitude = 0.0)
        identifyPass.join()

        verify { store.saveLastMovementTriggerLocation(GeofenceLocation(0.02, 0.0), any()) }
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
        // This path bypasses refreshAction, so it emits the storage record itself.
        verify { logger.logStorageLoaded(any(), hasAnchor = true) }
    }

    @Test
    fun restoreFromCache_givenMovementLocation_expectUsedAsEffectiveLocation() = runTest {
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

        verify { distanceFilter.nearest(cached, 50.0, 60.0, any(), any()) }
        verify(exactly = 0) { distanceFilter.nearest(any(), 12.34, 56.78, any(), any()) }
        coVerify { manager.replaceGeofencesForBootRestore(any()) }
        coVerify(exactly = 0) { manager.replaceGeofences(any(), any()) }
        coVerify(exactly = 0) { apiService.fetchGeofences(any()) }
    }

    @Test
    fun restoreFromCache_givenNoMovementLocationButAnchor_expectFallbackToAnchor() = runTest {
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

        verify(exactly = 0) { store.saveApiFetchStateIfCurrent(any(), any(), any()) }
    }

    @Test
    fun restoreFromCache_givenRefreshInFlight_expectStillRunsViaBootRestoreVariant() = runTest {
        // After a reboot a concurrent refresh would skip fences as "unchanged" and leave GMS empty.
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
            // Holds the slot so restoreFromCache enters mid-flight.
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

        coVerify { manager.replaceGeofencesForBootRestore(any()) }
    }

    @Test
    fun refresh_givenServerMaxMonitoringDistance_expectForwardedToDistanceFilter() = runTest {
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
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns emptySet() // newly registered
        every { store.getCachedConfig() } returns sampleConfig()
        // Synthesis follows the seeded containment, not a second geometry check.
        every { store.getEnteredIds() } returns setOf("biz-1")
        every { distanceFilter.nearest(cached, any(), any(), any(), any()) } returns cached
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refreshFromLiveFix(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 1) {
            transitionEmitter.emitWithExpectedState(
                geofenceId = "biz-1",
                transition = Event.GeofenceTransition.ENTER,
                userId = "user-42",
                timestampSeconds = any(),
                geofenceName = any(),
                metadata = any(),
                geosetIds = any(),
                monitorsExit = true,
                expectedUserStateGeneration = any(),
                expectedRegionRevision = any()
            )
        }
    }

    @Test
    fun handleMovement_givenNewlyRegisteredFenceDeviceInside_expectInitialEnterEmitted() = runTest {
        // The movement trigger fires on the OS's own fix, so it trusts geometry like a live fix.
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
            transitionEmitter.emitWithExpectedState(
                geofenceId = "biz-1",
                transition = Event.GeofenceTransition.ENTER,
                userId = "user-42",
                timestampSeconds = any(),
                geofenceName = any(),
                metadata = any(),
                geosetIds = any(),
                monitorsExit = true,
                expectedUserStateGeneration = any(),
                expectedRegionRevision = any()
            )
        }
    }

    /** A wipe suppresses synthesis for a requested fix, but a movement fix can't be stale. */
    @Test
    fun handleMovement_givenOsStateWiped_expectInitialEnterStillEmitted() = runTest {
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { secureUserStore.getUserId() } returns "user-42"
        every { clock.elapsedRealtime() } returns 1_000L
        // Stateful: the pass stamps this key mid-flight.
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
            transitionEmitter.emitWithExpectedState(
                geofenceId = "biz-1",
                transition = Event.GeofenceTransition.ENTER,
                userId = "user-42",
                timestampSeconds = any(),
                geofenceName = any(),
                metadata = any(),
                geosetIds = any(),
                monitorsExit = true,
                expectedUserStateGeneration = any(),
                expectedRegionRevision = any()
            )
        }
    }

    @Test
    fun refreshFromLiveFix_givenNewlyRegisteredEnterOnlyFenceDeviceInside_expectInitialEnterNotLatched() = runTest {
        // No EXIT ever releases an enter-only fence, so a latch suppresses every later arrival.
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
            transitionEmitter.emitWithExpectedState(
                geofenceId = "biz-1",
                transition = Event.GeofenceTransition.ENTER,
                userId = "user-42",
                timestampSeconds = any(),
                geofenceName = any(),
                metadata = any(),
                geosetIds = any(),
                monitorsExit = false,
                expectedUserStateGeneration = any(),
                expectedRegionRevision = any()
            )
        }
    }

    @Test
    fun refresh_givenExitClaimedWhileRegistering_expectNoSynthesizedEnter() = runTest {
        // The device left during the GMS await; an ENTER now would never be balanced.
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns emptySet() // newly registered
        every { store.getCachedConfig() } returns sampleConfig()
        // Geometry says inside; containment doesn't, because the exit was claimed.
        every { store.getEnteredIds() } returns emptySet()
        every { distanceFilter.nearest(cached, any(), any(), any(), any()) } returns cached
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 0) { transitionEmitter.emit(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun refresh_givenRebootWipedOsState_expectReRegistrationButNoInitialEnter() = runTest {
        // The launch anchor can predate the reboot, so no fence synthesizes.
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
        // g-1's radius grew 50 → 100 m, so it is re-added and synthesizes like a new fence.
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
            transitionEmitter.emitWithExpectedState(
                geofenceId = "g-1",
                transition = Event.GeofenceTransition.ENTER,
                userId = "user-42",
                timestampSeconds = any(),
                geofenceName = any(),
                metadata = any(),
                geosetIds = any(),
                monitorsExit = any(),
                expectedUserStateGeneration = any(),
                expectedRegionRevision = any()
            )
        }
    }

    @Test
    fun refresh_givenRegisteredFenceParamsChangedAnchorInside_expectNoInitialEnter() = runTest {
        // Same edit off the anchor: both the carried record and the anchor may be stale.
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
        // g-1's circle moved. GMS sends no EXIT for the old circle, so a carried record and mark
        // would drop the first arrival at the new one.
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

        // ~1.1 km away: outside the new circle.
        repository.refreshFromLiveFix(latitude = 0.01, longitude = 0.0)

        verify { store.reconcileEnteredIds(any(), any(), any(), setOf("g-1")) }
        verify { store.pruneEmittedEnterIds(match { "g-1" !in it }) }
    }

    @Test
    fun refreshFromLiveFix_givenOsStateWiped_expectNoInitialEnter() = runTest {
        // After a wipe every fence re-registers with INITIAL_TRIGGER_ENTER, so GMS reports arrivals.
        every { secureUserStore.getUserId() } returns "user-42"
        every { clock.currentTimeMillis() } returns 200_000_000_000L
        every { clock.elapsedRealtime() } returns 1_000L
        // Stateful: a fixed stub would pass whether or not the guard reads before the stamp.
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
        // Same moved circle off the anchor: retiring stale evidence seeds nothing, so it's safe.
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
        // An arrival reported during the await keeps the record, so its mark must stay too.
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
        // The arrival is newer than the fix, so the store declines the reset.
        every { store.reconcileEnteredIds(any(), any(), any(), any()) } returns emptySet()

        repository.refreshFromLiveFix(latitude = 0.01, longitude = 0.0)

        verify { store.reconcileEnteredIds(any(), any(), any(), setOf("g-1")) }
        verify { store.pruneEmittedEnterIds(match { "g-1" in it }) }
    }

    @Test
    fun refresh_givenFenceReRegisteredWhileDeviceInside_expectRecordAndMarkKept() = runTest {
        // Inside throughout; a reset would re-report this visit.
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
        // A carried record can outlive the visit, so geometry must agree before synthesis.
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

        // ~1.1 km north of g-1, outside its 100 m radius.
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
        // clearIdentify doesn't take stateMutex, so identity can change during the GMS await.
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

        // ~2.2 km move forces a local re-rank; the device is inside biz-1.
        repository.refresh(latitude = 0.02, longitude = 0.0)

        coVerify(exactly = 0) { transitionEmitter.emit(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun refresh_givenNewlyRegisteredFenceDeviceOutside_expectNoInitialEnter() = runTest {
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
        // The device may have moved while off, so the restore location can't be trusted.
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

    @Test
    fun refreshFromLiveFix_givenDeviceInsideAPolygonsWakeCircleButOutsideItsRing_expectItNotSeeded() = runTest {
        // A polygon's `radius` is its wake circle, not its ring.
        val circle = GeofenceRegion("biz-1", 0.0, 0.0, 1_000f)
        val ringCentre = metersOfLatitude(500)
        val polygon = GeofenceRegion(
            id = "poly-1",
            latitude = 0.0,
            longitude = 0.0,
            radius = 1_000f,
            polygonVertices = listOf(
                PolygonCoordinate(ringCentre - metersOfLatitude(20), -metersOfLatitude(20)),
                PolygonCoordinate(ringCentre - metersOfLatitude(20), metersOfLatitude(20)),
                PolygonCoordinate(ringCentre + metersOfLatitude(20), metersOfLatitude(20)),
                PolygonCoordinate(ringCentre + metersOfLatitude(20), -metersOfLatitude(20))
            )
        )
        val cached = listOf(circle, polygon)
        val reconciledInside = statefulStore(cached)

        repository.refresh(latitude = 0.0, longitude = 0.0)
        repository.refreshFromLiveFix(latitude = 0.0, longitude = 0.0)

        // The device is at the wake circle's centre, so a point-in-circle test would seed both.
        reconciledInside.last() shouldBeEqualTo setOf("biz-1")
        store.getEnteredIds() shouldContainSame setOf("biz-1")
    }

    /** Wires the keys an anchor pass writes, so a following live fix takes SKIP for the real reason. */
    private fun statefulStore(
        cached: List<GeofenceRegion>,
        preRegistered: Set<String> = emptySet()
    ): List<Set<String>?> {
        val reconciledInside = mutableListOf<Set<String>?>()
        var registeredIds = preRegistered
        // Null models key-absence, which is what `hasContainmentRecord` reads.
        var enteredIds: Set<String>? = null
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
        every { store.saveLastMovementTriggerLocation(any(), any()) } answers { movementLocation = firstArg() }
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
        // The anchor pass stamps every freshness input, so the live fix SKIPs but must still reseed.
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
        // A synthesized ENTER here would re-arm the mark and swallow the next real arrival.
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        statefulStore(cached)

        repository.refresh(latitude = 0.0, longitude = 0.0)
        repository.refreshFromLiveFix(latitude = 0.0, longitude = 0.0)
        store.claimExit("biz-1") shouldBeEqualTo true
        store.getEnteredIds().shouldBeEmpty()
        repository.refreshFromLiveFix(latitude = 0.0, longitude = 0.0)

        store.getEnteredIds() shouldContainSame setOf("biz-1")
        coVerify(exactly = 0) { transitionEmitter.emit(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun refreshFromLiveFix_givenFreshInputsAndDeviceOutside_expectNoEnterAndRecordWritten() = runTest {
        // An empty record still matters: it stops the EXIT guard deferring.
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        val reconciledInside = statefulStore(cached)

        repository.refresh(latitude = 0.0, longitude = 0.0)
        // ~555 m: outside the 100 m fence, inside both refresh radii.
        repository.refreshFromLiveFix(latitude = 0.005, longitude = 0.0)

        // Null from the anchor's registering pass, then the live fix's own empty judgement.
        reconciledInside shouldBeEqualTo listOf(null, emptySet())
        coVerify(exactly = 0) { transitionEmitter.emit(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun refreshFromLiveFix_givenCachedFenceNotRegistered_expectItLeftOutOfTheJudgement() = runTest {
        // A fence GMS never got can't send the EXIT that balances a recorded visit.
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
        // A sign-out can land before the write; seeding would hand the next user this containment.
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
        // The absent record keeps the EXIT guard deferring instead of dropping a real departure.
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        val reconciledInside = statefulStore(cached)

        repository.refresh(latitude = 0.0, longitude = 0.0)
        repository.refresh(latitude = 0.0, longitude = 0.0)

        verify(exactly = 1) { logger.logSyncSkippedFresh() }
        reconciledInside shouldBeEqualTo listOf(null)
        store.getEnteredIds().shouldBeEmpty()
    }

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

    @Test
    fun handleMovement_givenAdaptivePolygonRadius_expectPreservesItDuringLocalRerank() = runTest {
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns setOf("biz-1")
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns cached
        val registered = slot<List<GeofenceRegion>>()
        coEvery { manager.replaceGeofences(capture(registered), any()) } returns Result.success(Unit)

        repository.handleMovement(
            latitude = 0.01,
            longitude = 0.0,
            movementTriggerRadius = { 725f }
        )

        registered.captured.single {
            it.id == GeofenceConstants.MOVEMENT_TRIGGER_ID
        }.radius shouldBeEqualTo 725f
        // Logs the registered radius, not the configured one.
        val loggedRadius = slot<Double>()
        verify { logger.logMovementTriggerRegistered(any(), any(), capture(loggedRadius)) }
        loggedRadius.captured shouldBeEqualTo 725.0
        // Pinned, not any(): the staleness check reads this radius back.
        verify { store.saveLastMovementTriggerLocation(any(), 725f) }
    }

    @Test
    fun refreshFromLiveFix_givenFixInsideAPolygonsWakeCircle_expectPolygonNotSeeded() = runTest {
        // A fix inside the wake circle says nothing about containment in the ring.
        every { secureUserStore.getUserId() } returns "user-42"
        val polygon = GeofenceRegion(
            id = "campus",
            latitude = 0.0,
            longitude = 0.0,
            radius = 1_100f,
            polygonVertices = listOf(
                PolygonCoordinate(metersOfLatitude(500), 0.0),
                PolygonCoordinate(metersOfLatitude(500), 0.001),
                PolygonCoordinate(metersOfLatitude(600), 0.001),
                PolygonCoordinate(metersOfLatitude(600), 0.0)
            )
        )
        every { store.getRegisteredIds() } returns setOf("campus")
        coEvery { apiService.fetchGeofences(any()) } returns Result.success(sampleResponse(maxBusinessGeofences = 5))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns listOf(polygon)
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refreshFromLiveFix(latitude = 0.0, longitude = 0.0)

        verify {
            store.reconcileEnteredIds(
                registeredIds = any(),
                inside = emptySet(),
                sinceEpoch = any(),
                resetIds = any()
            )
        }
    }

    @Test
    fun refreshFromLiveFix_givenReRegisteredPolygonAndFixClearOfItsWakeCircle_expectNotRetired() = runTest {
        // Retiring polygon evidence is the evaluator's call; the sync fix only knows the wake circle.
        every { secureUserStore.getUserId() } returns "user-42"
        val polygon = GeofenceRegion(
            id = "campus",
            latitude = metersOfLatitude(5_000),
            longitude = 0.0,
            radius = 1_100f,
            polygonVertices = listOf(
                PolygonCoordinate(metersOfLatitude(4_900), 0.0),
                PolygonCoordinate(metersOfLatitude(4_900), 0.001),
                PolygonCoordinate(metersOfLatitude(5_100), 0.001),
                PolygonCoordinate(metersOfLatitude(5_100), 0.0)
            )
        )
        // Registered before this pass and not cache-equal, so it counts as re-registered.
        every { store.getRegisteredIds() } returns setOf("campus")
        every { store.getCachedRegions() } returns emptyList()
        coEvery { apiService.fetchGeofences(any()) } returns Result.success(sampleResponse(maxBusinessGeofences = 5))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns listOf(polygon)
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refreshFromLiveFix(latitude = 0.0, longitude = 0.0)

        verify {
            store.reconcileEnteredIds(
                registeredIds = any(),
                inside = any(),
                sinceEpoch = any(),
                resetIds = emptySet()
            )
        }
    }

    @Test
    fun refresh_givenEnteredPolygonVerticesChangeButFixRemainsInside_expectContainmentNotReset() = runTest {
        val previous = GeofenceRegion(
            id = "campus",
            latitude = 37.7750,
            longitude = -122.4194,
            radius = 250f,
            polygonVertices = listOf(
                PolygonCoordinate(37.7740, -122.4210),
                PolygonCoordinate(37.7740, -122.4180),
                PolygonCoordinate(37.7760, -122.4180),
                PolygonCoordinate(37.7760, -122.4210)
            )
        )
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getCachedRegions() } returns listOf(previous)
        every { store.getRegisteredIds() } returns
            setOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "campus")
        every { store.getEnteredIds() } returns setOf("campus")
        coEvery { apiService.fetchGeofences(any()) } returns Result.success(polygonResponse())
        every { distanceFilter.nearest(any(), any(), any(), any(), any(), any()) } answers { firstArg() }
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)
        val resetIds = slot<Set<String>>()
        every {
            store.reconcileEnteredIds(
                registeredIds = any(),
                inside = any(),
                sinceEpoch = any(),
                resetIds = capture(resetIds)
            )
        } returns emptySet()

        repository.refresh(latitude = 37.7750, longitude = -122.4194)

        resetIds.captured.shouldBeEmpty()
        verify { polygonController.resetEvidence("campus") }
    }

    @Test
    fun refresh_givenRegisteredButUnroutableFence_expectItIsReAddedNotSkippedAsUnchanged() = runTest {
        // unchangedRegisteredIds requires routable membership as well as registered.
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns null
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns setOf("biz-1")
        every { store.getRoutableRegisteredIds() } returns emptySet()
        every { store.getCachedConfig() } returns sampleConfig()
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns cached
        val existingSlot = slot<Set<String>>()
        coEvery { manager.replaceGeofences(any(), capture(existingSlot)) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        existingSlot.captured shouldNotContain "biz-1"
    }

    @Test
    fun refresh_givenFreshCacheButOnlyCleanupRegistrationsRemain_expectRevalidatesForCurrentUser() = runTest {
        val cached = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        every { secureUserStore.getUserId() } returns "user-B"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns setOf("biz-1")
        every { store.getRoutableRegisteredIds() } returns emptySet()
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getLastMovementTriggerLocation() } returns GeofenceLocation(0.0, 0.0)
        every { distanceFilter.nearest(cached, any(), any(), any(), any()) } returns cached
        val existingSlot = slot<Set<String>>()
        coEvery { manager.replaceGeofences(any(), capture(existingSlot)) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 0) { apiService.fetchGeofences(any()) }
        coVerify { manager.replaceGeofences(any(), any()) }
        existingSlot.captured.shouldBeEmpty()
        verify { store.saveRoutableRegisteredIdsIfCurrent(match { "biz-1" in it }, any()) }
    }

    @Test
    fun refresh_givenFullOsQuotaAndNearestSetRotates_expectOneStaleRemovedBeforeReplacementAdd() = runTest {
        val oldRegions = (1..99).map { index ->
            GeofenceRegion("old-${index.toString().padStart(3, '0')}", 0.0, 0.0, 100f)
        }
        val incoming = oldRegions.drop(1) + GeofenceRegion("new-100", 0.0, 0.0, 100f)
        var registeredIds = oldRegions.mapTo(mutableSetOf(), GeofenceRegion::id) +
            GeofenceConstants.MOVEMENT_TRIGGER_ID
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getCachedRegions() } returns oldRegions
        every { store.getRegisteredIds() } answers { registeredIds }
        every { store.saveRegisteredIds(any()) } answers {
            registeredIds = firstArg()
        }
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 99))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns incoming
        coEvery { manager.removeGeofencesByIds(listOf("old-001")) } returns Result.success(Unit)
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerifyOrder {
            manager.removeGeofencesByIds(listOf("old-001"))
            manager.replaceGeofences(any(), any())
        }
    }

    @Test
    fun refresh_givenAChangedFenceAndNoNetGrowth_expectNothingPreRemovedBeforeTheAdd() = runTest {
        // Re-adding a changed fence replaces its GMS slot, so it isn't growth that needs pre-removal.
        val oldRegions = (1..99).map { index ->
            GeofenceRegion("old-${index.toString().padStart(3, '0')}", 0.0, 0.0, 100f)
        }
        // Drops old-001 and re-sends old-002 with new geometry: 98 requested, nothing new.
        val incoming = listOf(GeofenceRegion("old-002", 0.0, 0.0, 200f)) + oldRegions.drop(2)
        var registeredIds = oldRegions.mapTo(mutableSetOf(), GeofenceRegion::id) +
            GeofenceConstants.MOVEMENT_TRIGGER_ID
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getCachedRegions() } returns oldRegions
        every { store.getRegisteredIds() } answers { registeredIds }
        every { store.saveRegisteredIds(any()) } answers { registeredIds = firstArg() }
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 99))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns incoming
        coEvery { manager.removeGeofencesByIds(any()) } returns Result.success(Unit)
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerifyOrder {
            manager.replaceGeofences(any(), any())
            manager.removeGeofencesByIds(listOf("old-001"))
        }
    }

    @Test
    fun refresh_givenPolygonResponseAndFixInsideTrigger_expectRegistersWithoutStartingSampling() = runTest {
        every { secureUserStore.getUserId() } returns "user-42"
        every { clock.currentTimeMillis() } returns 200_000_000_000L
        every { store.getLastSyncTimestamp() } returns 1_000L
        every { store.getCachedRegions() } returns emptyList()
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any()) } returns Result.success(polygonResponse())
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } answers { firstArg() }
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        repository.refresh(latitude = 37.7750, longitude = -122.4194)

        verify { polygonController.reconcileRegisteredPolygons(setOf("campus")) }
        verify(exactly = 0) { polygonController.activate(any(), any<Long>(), any<Int>()) }
        coVerify(exactly = 0) {
            transitionEmitter.emitWithExpectedState(
                geofenceId = "campus",
                transition = Event.GeofenceTransition.ENTER,
                userId = any(),
                timestampSeconds = any(),
                geofenceName = any(),
                metadata = any(),
                geosetIds = any(),
                monitorsExit = any(),
                expectedUserStateGeneration = any(),
                expectedRegionRevision = any()
            )
        }
    }

    @Test
    fun refresh_givenRetainedStalePolygon_expectLocalPassRetriesRemovalWithoutRankingIt() = runTest {
        val stalePolygon = GeofenceRegion(
            id = "biz-old",
            latitude = 37.775,
            longitude = -122.4194,
            radius = 200f,
            polygonVertices = listOf(
                io.customer.geofence.polygon.PolygonCoordinate(37.7745, -122.4200),
                io.customer.geofence.polygon.PolygonCoordinate(37.7745, -122.4188),
                io.customer.geofence.polygon.PolygonCoordinate(37.7755, -122.4188)
            )
        )
        val currentRegion = GeofenceRegion("biz-new", 0.0, 0.0, 100f)
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getLastMovementTriggerLocation() } returns GeofenceLocation(0.0, 0.0)
        every { store.getCachedRegions() } returns listOf(currentRegion)
        every { store.getRetainedRegisteredRegions() } returns listOf(stalePolygon)
        every { store.getRegisteredIds() } returns setOf("biz-old")
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns listOf(currentRegion)
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)
        coEvery { manager.removeGeofencesByIds(any()) } returns
            Result.failure(RuntimeException("remove boom"))

        repository.refresh(latitude = 0.02, longitude = 0.0)

        coVerify(exactly = 0) { apiService.fetchGeofences(any()) }
        coVerify { manager.removeGeofencesByIds(match { "biz-old" in it }) }
        verify { store.saveRetainedRegisteredRegions(match { it == listOf(stalePolygon) }) }
        verify {
            distanceFilter.nearest(
                regions = listOf(currentRegion),
                latitude = any(),
                longitude = any(),
                max = any(),
                maxDistanceMeters = any()
            )
        }
    }

    @Test
    fun refresh_givenStalePolygonRemovalFails_expectShapeRetainedOnlyForCleanupRouting() = runTest {
        val stalePolygon = GeofenceRegion(
            id = "biz-old",
            latitude = 37.775,
            longitude = -122.4194,
            radius = 200f,
            polygonVertices = listOf(
                io.customer.geofence.polygon.PolygonCoordinate(37.7745, -122.4200),
                io.customer.geofence.polygon.PolygonCoordinate(37.7745, -122.4188),
                io.customer.geofence.polygon.PolygonCoordinate(37.7755, -122.4188)
            )
        )
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getRegisteredIds() } returns setOf("biz-old")
        every { store.getCachedRegions() } returns listOf(stalePolygon)
        coEvery { apiService.fetchGeofences(any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns
            listOf(GeofenceRegion("biz-new", 0.0, 0.0, 100f))
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)
        coEvery { manager.removeGeofencesByIds(any()) } returns
            Result.failure(RuntimeException("remove boom"))
        val cached = slot<List<GeofenceRegion>>()
        val retained = slot<List<GeofenceRegion>>()
        every { store.saveCachedRegions(capture(cached)) } returns Unit
        every { store.saveRetainedRegisteredRegions(capture(retained)) } returns Unit

        repository.refresh(latitude = 0.0, longitude = 0.0)

        cached.captured.any { it.id == "biz-old" } shouldBeEqualTo false
        retained.captured.any { it.id == "biz-old" && it.isPolygon } shouldBeEqualTo true
        verify {
            polygonController.reconcileRegisteredPolygons(match { "biz-old" !in it })
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun refresh_givenUserSwitchWhilePreviousUsersFetchIsInFlight_expectNewUserGetsFollowUpPass() = runTest {
        var currentUser = "user-A"
        // A relaxed mock pins the generation at 0, making a user switch look like the same session.
        var generation = 1L
        val firstFetchStarted = CompletableDeferred<Unit>()
        val finishFirstFetch = CompletableDeferred<Unit>()
        val fetchCount = AtomicInteger(0)
        every { secureUserStore.getUserId() } answers { currentUser }
        every { store.userStateGeneration() } answers { generation }
        every { store.activeUserSessionId() } answers { currentUser }
        every { store.getLastSyncTimestamp() } returns null
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any()) } coAnswers {
            if (fetchCount.incrementAndGet() == 1) {
                firstFetchStarted.complete(Unit)
                finishFirstFetch.await()
            }
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        }
        every { distanceFilter.nearest(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)

        val userARefresh = launch { repository.refresh(latitude = 1.0, longitude = 1.0) }
        firstFetchStarted.await()
        currentUser = "user-B"
        generation = 2L
        val userBRefresh = launch { repository.refresh(latitude = 2.0, longitude = 2.0) }
        runCurrent()
        finishFirstFetch.complete(Unit)
        advanceUntilIdle()
        userARefresh.join()
        userBRefresh.join()

        fetchCount.get() shouldBeEqualTo 2
        coVerify(exactly = 1) { manager.replaceGeofences(any(), any()) }
        verify(exactly = 0) { logger.logSyncSkipped(match { it.contains("already in progress") }) }
    }

    @Test
    fun restoreFromCache_givenNoOsWipeAndDeviceInside_expectStillNoInitialEnter() = runTest {
        // Record and geometry both say inside with OS state intact; only anchor handling blocks it.
        val movementLoc = GeofenceLocation(latitude = 50.0, longitude = 60.0)
        val cached = listOf(GeofenceRegion("biz-1", 50.0, 60.0, 100f))
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastMovementTriggerLocation() } returns movementLoc
        every { store.getLastApiFetchLocation() } returns null
        every { store.getCachedConfig() } returns sampleConfig()
        every { store.getCachedRegions() } returns cached
        every { store.getRegisteredIds() } returns emptySet()
        every { store.getEnteredIds() } returns setOf("biz-1")
        // Uptime advanced: no reboot.
        every { clock.elapsedRealtime() } returns 900_000L
        every { store.getLastRegistrationUptime() } returns 500_000L
        every { store.getLastRegistrationPackageUpdateTime() } returns 1_000L
        every { packageInfo.lastUpdateTimeMs() } returns 1_000L
        every { distanceFilter.nearest(cached, 50.0, 60.0, any(), any()) } returns cached
        coEvery { manager.replaceGeofencesForBootRestore(any()) } returns Result.success(Unit)

        repository.restoreFromCache()

        coVerify(exactly = 0) {
            transitionEmitter.emitWithExpectedState(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        }
    }

    private fun polygonResponse(): GeofenceApiResponse = jsonSerializer.decode(
        GeofenceApiResponse.serializer(),
        """
        {
          "config": {
            "local_refresh_trigger_radius": 1000,
            "remote_fetch_refresh_trigger_radius": 5000,
            "remote_fetch_refresh_expiry_time": 86400000,
            "duplicate_events_expiry_time": 3600000,
            "android": { "max_business_geofence": 3 }
          },
          "geofences": [{
            "id": "campus",
            "shape": "polygon",
            "geometry": {
              "type": "Polygon",
              "coordinates": [[
                [-122.4200, 37.7745],
                [-122.4188, 37.7745],
                [-122.4188, 37.7755],
                [-122.4200, 37.7755],
                [-122.4200, 37.7745]
              ]]
            },
            "enclosing_circle": {
              "latitude": 37.7750,
              "longitude": -122.4194,
              "base_radius_m": 100
            }
          }]
        }
        """.trimIndent()
    )
}
