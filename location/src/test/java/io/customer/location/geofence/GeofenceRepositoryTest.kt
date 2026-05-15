package io.customer.location.geofence

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.location.geofence.api.GeofenceApiResponse
import io.customer.location.geofence.api.GeofenceApiResponseParser
import io.customer.location.geofence.api.GeofenceApiService
import io.customer.location.geofence.store.GeofenceRegionStore
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
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
    private val parser = GeofenceApiResponseParser()

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
    fun refresh_givenNoUserId_expectSkipAndSuccessAndNoApiCall() = runTest {
        every { secureUserStore.getUserId() } returns null

        val result = repository.refresh(latitude = 12.34, longitude = 56.78)

        result.isSuccess shouldBeEqualTo true
        coVerify(exactly = 0) { apiService.fetchGeofences(any(), any(), any()) }
        coVerify(exactly = 0) { manager.addGeofences(any()) }
        verify(exactly = 0) { store.saveAll(any()) }
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
        verify(exactly = 0) { store.saveAll(any()) }
        verify(exactly = 0) { store.setLastSyncTimestamp(any()) }
        coVerify(exactly = 0) { manager.addGeofences(any()) }
    }

    @Test
    fun refresh_givenApiSuccess_expectPersistedAndFilteredByConfigMaxAndRegistered() = runTest {
        every { secureUserStore.getUserId() } returns "user-42"
        val filtered = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 100f))
        coEvery { apiService.fetchGeofences("user-42", 12.34, 56.78) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 3))
        every { distanceFilter.nearest(any(), 12.34, 56.78, 3) } returns filtered
        coEvery { manager.addGeofences(filtered) } returns Result.success(Unit)

        val result = repository.refresh(latitude = 12.34, longitude = 56.78)

        result.isSuccess shouldBeEqualTo true
        verify { store.saveAll(any()) }
        verify { store.setLastSyncTimestamp(any()) }
        verify { distanceFilter.nearest(any(), 12.34, 56.78, 3) }
        coVerify { manager.addGeofences(filtered) }
        verify { logger.logSyncSucceeded(filtered.size) }
    }

    @Test
    fun refresh_givenManagerFailure_expectFailureAndRegionsStillPersisted() = runTest {
        val error = RuntimeException("gms boom")
        every { secureUserStore.getUserId() } returns "user-42"
        coEvery { apiService.fetchGeofences(any(), any(), any()) } returns
            Result.success(sampleResponse(maxBusinessGeofences = 5))
        every { distanceFilter.nearest(any(), any(), any(), any()) } returns emptyList()
        coEvery { manager.addGeofences(any()) } returns Result.failure(error)

        val result = repository.refresh(latitude = 0.0, longitude = 0.0)

        result.isFailure shouldBeEqualTo true
        result.exceptionOrNull() shouldBeEqualTo error
        verify { store.saveAll(any()) }
        verify(exactly = 0) { logger.logSyncSucceeded(any()) }
    }

    @Test
    fun refresh_givenSecondCallWhileFirstInFlight_expectSecondDroppedAndOnlyOneApiCall() = runTest {
        // In-flight gate dedups concurrent triggers: the second refresh returns
        // success immediately without firing a redundant API call or attempting
        // a second OS registration.
        every { secureUserStore.getUserId() } returns "user-42"
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
        verify(exactly = 0) { store.saveAll(any()) }
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
        verify(exactly = 0) { store.saveAll(any()) }
        coVerify(exactly = 0) { manager.addGeofences(any()) }
        verify { logger.logSyncSkipped(match { it.contains("user changed") }) }
    }

    private fun sampleResponse(maxBusinessGeofences: Int): GeofenceApiResponse {
        val json = """
            {
              "config": {
                "movement_trigger_radius": 1000,
                "local_refresh_trigger_radius": 5000,
                "remote_fetch_refresh_trigger_radius": 10000,
                "remote_fetch_refresh_expiry_time": 86400,
                "duplicate_events_expiry_time": 3600,
                "android": { "max_business_geofence": $maxBusinessGeofences }
              },
              "geofences": [
                { "id": "g-1", "name": "n1", "latitude": 0.0, "longitude": 0.0, "radius": 100, "transition_types": ["enter"], "last_updated": 1 }
              ]
            }
        """.trimIndent()
        return parser.parse(json)
    }
}
