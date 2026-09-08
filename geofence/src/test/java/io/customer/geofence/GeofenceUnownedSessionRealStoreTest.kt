package io.customer.geofence

import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.api.GeofenceApiResponse
import io.customer.geofence.api.GeofenceApiService
import io.customer.geofence.store.GeofenceRegionStoreImpl
import io.customer.sdk.core.util.Clock
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldContainSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The trade made by opening an unowned session as a switch, against the real store rather than a
 * stub: registrations survive it and a refresh re-arms them. Both halves matter, so both are here.
 */
@RunWith(RobolectricTestRunner::class)
class GeofenceUnownedSessionRealStoreTest : RobolectricTest() {

    private lateinit var store: GeofenceRegionStoreImpl
    private lateinit var repository: GeofenceRepositoryImpl

    private val apiService: GeofenceApiService = mockk(relaxed = true)
    private val manager: GeofenceManager = mockk(relaxed = true)
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val clock: Clock = mockk(relaxed = true)
    private val packageInfo: GeofencePackageInfo = mockk { every { lastUpdateTimeMs() } returns null }
    private val jsonSerializer = GeofenceJsonSerializer()

    private val fence = GeofenceRegion("biz-1", 0.0, 0.0, 100f)

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { argument(ApplicationArgument(applicationMock)) })
        store = GeofenceRegionStoreImpl(
            context = applicationMock,
            jsonSerializer = jsonSerializer,
            logger = mockk(relaxed = true)
        )
        store.clearAll()
        every { secureUserStore.getUserId() } returns USER
        every { clock.elapsedRealtime() } returns 10_000L
        every { clock.currentTimeMillis() } returns System.currentTimeMillis()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)
        repository = GeofenceRepositoryImpl(
            apiService = apiService,
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
        // An install upgraded from a version that stored registrations but no session owner.
        store.saveCachedRegions(listOf(fence))
        store.saveCachedConfig(sampleConfig())
        store.saveRegisteredIds(setOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, fence.id))
        store.saveLastApiFetchLocation(GeofenceLocation(0.0, 0.0))
        store.setLastSyncTimestamp(System.currentTimeMillis())
        store.saveLastMovementTriggerLocation(GeofenceLocation(0.0, 0.0))
        store.setLastRegistrationUptime(clock.elapsedRealtime())
    }

    @Test
    fun unownedSession_expectRegistrationsKeptButNothingRoutableUntilARefreshArmsThem() = runTest {
        store.activeUserSessionId().shouldBeNull()
        val generationBefore = store.userStateGeneration()
        coEvery { apiService.fetchGeofences(any()) } returns Result.success(sampleResponse())

        store.beginUserSession(USER)

        // Opened as a switch, not adopted: the generation moves, so a pass that began before this
        // cannot arm routing for it. The cost is registered but unarmed, so the OS keeps reporting
        // and nothing is attributed to this user yet.
        store.userStateGeneration() shouldBeEqualTo generationBefore + 1L
        store.getRegisteredIds() shouldContain fence.id
        store.getRoutableRegisteredIds().shouldBeEmpty()

        repository.refresh(latitude = 0.0, longitude = 0.0)

        store.getRoutableRegisteredIds() shouldContainSame store.getRegisteredIds()
        store.getCachedRegions().map { it.id } shouldContainSame listOf("g-1")
        store.userStateGeneration() shouldBeEqualTo generationBefore + 1L
    }

    @Test
    fun unownedSessionWithNoNetwork_expectMovementReArmsRoutingFromTheCache() = runTest {
        // The justification for the trade: an upgrade with no network still recovers, because the
        // movement trigger is exempt from the unarmed drop and its pass re-ranks from the catalog.
        coEvery { apiService.fetchGeofences(any()) } returns Result.failure(IOException("offline"))

        store.beginUserSession(USER)
        repository.refresh(latitude = 0.0, longitude = 0.0)

        // Not just "empty": empty because the fetch was attempted and failed. Without this the
        // assertion also holds when the pass never fetched at all.
        coVerify(exactly = 1) { apiService.fetchGeofences(any()) }
        store.getRoutableRegisteredIds().shouldBeEmpty()

        repository.handleMovement(latitude = 0.0, longitude = 0.0)

        store.getRoutableRegisteredIds() shouldContainSame store.getRegisteredIds()
        store.getRoutableRegisteredIds() shouldContain fence.id
    }

    private fun sampleResponse(): GeofenceApiResponse {
        val json = """
            {
              "config": {
                "local_refresh_trigger_radius": 1000,
                "remote_fetch_refresh_trigger_radius": 5000,
                "remote_fetch_refresh_expiry_time": 86400000,
                "duplicate_events_expiry_time": 3600000,
                "android": { "max_business_geofence": 19 }
              },
              "geofences": [
                { "id": "g-1", "name": "n1", "latitude": 0.0, "longitude": 0.0, "radius": 100, "transition_types": ["enter"], "last_updated": 1 }
              ]
            }
        """.trimIndent()
        return jsonSerializer.decode(GeofenceApiResponse.serializer(), json)
    }

    private fun sampleConfig() = GeofenceConfig(
        localRefreshTriggerRadius = 1_000f,
        remoteFetchRefreshTriggerRadius = 5_000f,
        remoteFetchRefreshExpiry = 86_400_000L,
        duplicateEventsExpiry = 3_600_000L,
        maxBusinessGeofences = 19,
        maxMonitoringDistance = GeofenceConstants.NO_MONITORING_DISTANCE_CAP_METERS
    )

    private companion object {
        const val USER = "user-42"
    }
}
