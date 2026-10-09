package io.customer.geofence

import android.content.Context
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.api.GeofenceApiRegion
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
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * An install upgraded from an SDK that cached its catalog before dwell existed, through the real
 * store. The legacy bytes are written literally, with a fresh sync stamp and live registrations.
 */
@RunWith(RobolectricTestRunner::class)
class GeofenceCatalogUpgradeRealStoreTest : RobolectricTest() {
    private val manager: GeofenceManager = mockk(relaxed = true)
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val clock: Clock = mockk(relaxed = true)
    private val packageInfo: GeofencePackageInfo = mockk()
    private var packageUpdateTime = UPDATE_TIME
    private lateinit var store: GeofenceRegionStoreImpl

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { argument(ApplicationArgument(applicationMock)) })
        every { secureUserStore.getUserId() } returns USER_ID
        every { clock.currentTimeMillis() } returns NOW_MS
        every { clock.elapsedRealtime() } returns 10_000L
        every { packageInfo.lastUpdateTimeMs() } answers { packageUpdateTime }
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)
        coEvery { manager.removeGeofencesByIds(any()) } returns Result.success(Unit)
        store = newStore().also { it.clearAll() }
        seedLegacyInstall()
    }

    @Test
    fun refresh_givenFreshLegacyCatalog_expectFetchedOnceAcrossRecreationAndReset() = runTest {
        val api = api(Result.success(dwellResponse()))
        repository(api).refresh(0.0, 0.0).isSuccess.shouldBeTrue()
        coVerify(exactly = 1) { api.fetchGeofences(any()) }
        store.getCachedRegions().single().dwellThresholdSeconds shouldBeEqualTo 60

        // Process recreation, then sign-out and sign-in with a fresh stamp.
        val recreated = newStore()
        repository(api, recreated).refresh(0.0, 0.0)
        recreated.completeUserReset(recreated.userStateGeneration(), osRegistrationsCleared = true)
        recreated.beginUserSession(USER_ID)
        recreated.saveApiFetchStateIfCurrent(ORIGIN, NOW_MS, recreated.userStateGeneration()).shouldBeTrue()
        repository(api, recreated).refresh(0.0, 0.0)
        coVerify(exactly = 1) { api.fetchGeofences(any()) }
    }

    @Test
    fun refresh_givenCurrentResponseWithDwellOffOrNoGeofences_expectAcknowledgedWithoutRefetch() = runTest {
        val responses = listOf(
            GeofenceApiResponse(geofences = listOf(GeofenceApiRegion(id = "legacy", latitude = 0.0, longitude = 0.0, radius = 100.0))),
            GeofenceApiResponse(geofences = emptyList())
        )
        responses.forEach { response ->
            store.clearAll()
            seedLegacyInstall()
            val api = api(Result.success(response))
            repository(api).refresh(0.0, 0.0).isSuccess.shouldBeTrue()
            repository(api, newStore()).refresh(0.0, 0.0)
            coVerify(exactly = 1) { api.fetchGeofences(any()) }
        }
    }

    @Test
    fun refresh_givenOfflineAfterAppUpdate_expectLegacyCacheReRegisteredKeptAndRetried() = runTest {
        packageUpdateTime = UPDATE_TIME + 1
        val api = api(Result.failure(IOException("offline")))
        val repository = repository(api)
        repository.refresh(0.0, 0.0).isFailure.shouldBeTrue()
        // The update cancelled the OS fences; routable ids that survived it must not stand in for them.
        coVerify(exactly = 1) { manager.replaceGeofences(any(), any()) }
        repository.refresh(0.0, 0.0)
        coVerify(exactly = 2) { api.fetchGeofences(any()) }
        rawCatalog() shouldBeEqualTo LEGACY_CATALOG
    }

    @Test
    fun refresh_givenCurrentResponseNotRegistered_expectLegacyCacheKeptAndRetried() = runTest {
        assertFailedUpgradeRegistrationRetried(osStateWiped = false)
    }

    @Test
    fun refresh_givenCurrentResponseNotRegisteredAfterAppUpdate_expectLegacyFallbackThenRetried() = runTest {
        assertFailedUpgradeRegistrationRetried(osStateWiped = true)
    }

    @Test
    fun handleMovement_givenLegacyCatalogWithinFetchRadius_expectCurrentCatalogFetched() = runTest {
        val api = api(Result.success(dwellResponse()))
        repository(api).handleMovement(0.0, 0.0).isSuccess.shouldBeTrue()
        coVerify(exactly = 1) { api.fetchGeofences(any()) }
        store.getCachedRegions().single().dwellThresholdSeconds shouldBeEqualTo 60
    }

    private val rawPrefs get() = applicationMock.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun rawCatalog(): String? = rawPrefs.getString("cached_regions", null)

    /**
     * A current catalog GMS rejects is never acknowledged: the catalog and its marker stay legacy, and
     * the next pass re-fetches and re-adds the fence with its dwell instead of keeping the old one.
     * After a wipe, the last-known catalog keeps monitoring in the meantime.
     */
    private suspend fun assertFailedUpgradeRegistrationRetried(osStateWiped: Boolean) {
        if (osStateWiped) packageUpdateTime = UPDATE_TIME + 1
        val registered = mutableListOf<List<GeofenceRegion>>()
        val kept = mutableListOf<Set<String>>()
        coEvery { manager.replaceGeofences(capture(registered), capture(kept)) } returnsMany listOf(
            Result.failure(IOException("gms")),
            Result.success(Unit)
        )
        val api = api(Result.success(dwellResponse()))
        val repository = repository(api)

        repository.refresh(0.0, 0.0).isFailure.shouldBeTrue()
        registered.first().business("legacy").dwellThresholdSeconds shouldBeEqualTo 60
        if (osStateWiped) {
            // The fallback fully re-registers the last-known definition, without dwell.
            registered.size shouldBeEqualTo 2
            registered[1].business("legacy").dwellThresholdSeconds shouldBeEqualTo 0
            kept[1].isEmpty().shouldBeTrue()
        } else {
            registered.size shouldBeEqualTo 1
        }
        rawCatalog() shouldBeEqualTo LEGACY_CATALOG
        store.cachedCatalogNeedsRefresh().shouldBeTrue()

        repository.refresh(0.0, 0.0).isSuccess.shouldBeTrue()
        coVerify(exactly = 2) { api.fetchGeofences(any()) }
        registered.last().business("legacy").dwellThresholdSeconds shouldBeEqualTo 60
        ("legacy" in kept.last()).shouldBeFalse()
        store.cachedCatalogNeedsRefresh().shouldBeFalse()
        store.getCachedRegions().single().dwellThresholdSeconds shouldBeEqualTo 60
    }

    private fun List<GeofenceRegion>.business(id: String) = single { it.id == id }

    private fun seedLegacyInstall() {
        rawPrefs.edit().putString("cached_regions", LEGACY_CATALOG).commit().shouldBeTrue()
        store.beginUserSession(USER_ID)
        store.saveApiFetchStateIfCurrent(ORIGIN, NOW_MS, store.userStateGeneration()).shouldBeTrue()
        val ids = setOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "legacy")
        store.saveRegisteredIds(ids)
        store.saveRoutableRegisteredIds(ids)
        store.saveLastMovementTriggerLocation(ORIGIN, 200f)
        store.setLastRegistrationUptime(5_000L)
        store.setLastRegistrationBootSession(BOOT)
        store.setLastRegistrationPackageUpdateTime(UPDATE_TIME)
    }

    private fun dwellResponse() = GeofenceApiResponse(
        geofences = listOf(GeofenceApiRegion(id = "legacy", latitude = 0.0, longitude = 0.0, radius = 100.0, dwellThresholdSeconds = 60))
    )

    private fun api(result: Result<GeofenceApiResponse>): GeofenceApiService = mockk {
        coEvery { fetchGeofences(any()) } returns result
    }

    private fun newStore() = GeofenceRegionStoreImpl(
        context = applicationMock,
        jsonSerializer = GeofenceJsonSerializer(),
        logger = mockk(relaxed = true)
    )

    private fun repository(api: GeofenceApiService, regionStore: GeofenceRegionStoreImpl = store) =
        GeofenceRepositoryImpl(
            apiService = api,
            store = regionStore,
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

    private companion object {
        const val USER_ID = "user-42"
        const val BOOT = "boot"
        const val NOW_MS = 1_700_000_000_000L
        const val UPDATE_TIME = 1_000L
        const val PREFS_NAME = "io.customer.sdk.geofence_regions.com.example.test_app"

        // Bytes an SDK that predates dwell wrote for this fence.
        const val LEGACY_CATALOG = """[{"id":"legacy","latitude":0.0,"longitude":0.0,"radius":100.0}]"""
        val ORIGIN = GeofenceLocation(0.0, 0.0)
    }
}
