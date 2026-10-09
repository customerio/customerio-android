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
import io.mockk.slot
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldContainSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Opening an unowned session (an upgraded install), against the real store. It behaves as a user
 * switch, but registrations and location anchors survive it and a refresh re-arms routing.
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
            bootSessionProvider = { BOOT },
            packageInfo = packageInfo,
            logger = mockk(relaxed = true)
        )
        // An install upgraded from a version that stored registrations but no session owner.
        store.saveCachedRegions(listOf(fence))
        store.saveCachedConfig(sampleConfig())
        store.saveRegisteredIds(setOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, fence.id))
        store.saveApiFetchStateIfCurrent(
            location = GeofenceLocation(0.0, 0.0),
            syncTimestamp = System.currentTimeMillis(),
            expectedUserStateGeneration = store.userStateGeneration()
        )
        store.saveLastMovementTriggerLocation(GeofenceLocation(0.0, 0.0), 1_000f)
        store.setLastRegistrationUptime(clock.elapsedRealtime())
        // Registered in this boot, so the OS still holds these fences: only an unarmed session needs
        // the cache re-rank.
        store.setLastRegistrationBootSession(BOOT)
    }

    @Test
    fun unownedSession_expectRegistrationsKeptButNothingRoutableUntilARefreshArmsThem() = runTest {
        store.activeUserSessionId().shouldBeNull()
        val generationBefore = store.userStateGeneration()
        coEvery { apiService.fetchGeofences(any()) } returns Result.success(sampleResponse())

        store.beginUserSession(USER)

        store.userStateGeneration() shouldBeEqualTo generationBefore + 1L
        store.getRegisteredIds() shouldContain fence.id
        store.getRoutableRegisteredIds().shouldBeEmpty()

        repository.refresh(latitude = 0.0, longitude = 0.0)

        store.getRoutableRegisteredIds() shouldContainSame store.getRegisteredIds()
        store.getCachedRegions().map { it.id } shouldContainSame listOf("g-1")
        store.userStateGeneration() shouldBeEqualTo generationBefore + 1L
    }

    @Test
    fun unownedSessionWithNoNetwork_expectRoutingArmedFromTheCachedCatalog() = runTest {
        // Otherwise an offline upgrade stays registered but unrouted until the network returns.
        coEvery { apiService.fetchGeofences(any()) } returns Result.failure(IOException("offline"))

        store.beginUserSession(USER)
        val result = repository.refresh(latitude = 0.0, longitude = 0.0)

        coVerify(exactly = 1) { apiService.fetchGeofences(any()) }
        result.isFailure shouldBeEqualTo true
        store.getRoutableRegisteredIds() shouldContainSame store.getRegisteredIds()
        store.getRoutableRegisteredIds() shouldContain fence.id
    }

    @Test
    fun armedSessionWithNoNetwork_expectNoLocalReRank() = runTest {
        // The re-rank only recovers an unarmed session; it is not a general fallback.
        store.beginUserSession(USER)
        store.saveRoutableRegisteredIdsIfCurrent(store.getRegisteredIds(), store.userStateGeneration())
            .shouldBeTrue()
        coEvery { apiService.fetchGeofences(any()) } returns Result.failure(IOException("offline"))

        repository.refresh(latitude = 0.0, longitude = 0.0)

        // Otherwise "did not re-register" could pass because the pass never went remote.
        coVerify(exactly = 1) { apiService.fetchGeofences(any()) }
        coVerify(exactly = 0) { manager.replaceGeofences(any(), any()) }
        store.getRoutableRegisteredIds() shouldContainSame store.getRegisteredIds()
    }

    @Test
    fun unownedSessionWithNoNetwork_expectMovementAlsoReArmsFromTheCache() = runTest {
        coEvery { apiService.fetchGeofences(any()) } returns Result.failure(IOException("offline"))
        store.beginUserSession(USER)
        store.saveRoutableRegisteredIdsIfCurrent(emptySet(), store.userStateGeneration()).shouldBeTrue()

        repository.handleMovement(latitude = 0.0, longitude = 0.0)

        store.getRoutableRegisteredIds() shouldContain fence.id
    }

    @Test
    fun unownedSessionThenBootRestore_expectTheCachedFencesRegisteredAgain() = runTest {
        // A reboot restores from the persisted anchor alone, so opening the session must keep it.
        val restored = slot<List<GeofenceRegion>>()
        coEvery { manager.replaceGeofencesForBootRestore(capture(restored)) } returns Result.success(Unit)

        store.beginUserSession(USER)
        val result = repository.restoreFromCache()

        result.isSuccess.shouldBeTrue()
        coVerify(exactly = 1) { manager.replaceGeofencesForBootRestore(any()) }
        restored.captured.map { it.id } shouldContain fence.id
    }

    @Test
    fun switchBetweenTwoKnownUsers_expectTheAnchorsDroppedWithTheRestOfTheSession() = runTest {
        // Anchors survive adoption only because the registrations are the same user's.
        store.beginUserSession(USER)
        store.saveApiFetchStateIfCurrent(
            location = GeofenceLocation(10.0, 20.0),
            syncTimestamp = System.currentTimeMillis(),
            expectedUserStateGeneration = store.userStateGeneration()
        )
        store.saveLastMovementTriggerLocation(GeofenceLocation(10.0, 20.0), 1_000f)

        store.beginUserSession("someone-else")

        store.getLastApiFetchLocation().shouldBeNull()
        store.getLastMovementTriggerLocation().shouldBeNull()
    }

    @Test
    fun unownedSessionOpenedByAppLaunchThenBootRestore_expectTheCachedFencesRegisteredAgain() = runTest {
        // onAppLaunch opens the session through the absent-only entry point.
        val restored = slot<List<GeofenceRegion>>()
        coEvery { manager.replaceGeofencesForBootRestore(capture(restored)) } returns Result.success(Unit)

        store.beginUserSessionIfAbsent(USER)
        repository.restoreFromCache()

        coVerify(exactly = 1) { manager.replaceGeofencesForBootRestore(any()) }
        restored.captured.map { it.id } shouldContain fence.id
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
        const val BOOT = "boot"
    }
}
