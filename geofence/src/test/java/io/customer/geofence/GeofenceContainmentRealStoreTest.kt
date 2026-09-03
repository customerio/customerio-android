package io.customer.geofence

import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.api.GeofenceApiService
import io.customer.geofence.store.GeofenceRegionStoreImpl
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
 * The containment-only pass against the real [GeofenceRegionStoreImpl] rather than a stub of it.
 * [GeofenceRepositoryTest] models reconcile's semantics by hand, so it cannot show that this path
 * arms the guard the receiver actually reads — only the real store's epoch filter and key-absence
 * grace can.
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
        store.setLastSyncTimestamp(System.currentTimeMillis())
        store.saveRegisteredIds(setOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, fence.id))
        store.saveLastMovementTriggerLocation(GeofenceLocation(0.0, 0.0))
        store.setLastRegistrationUptime(clock.elapsedRealtime())
    }

    @Test
    fun anchorOnlyState_expectGuardStillDeferring() {
        // Control: with no live fix yet the key is absent, so the receiver's guard fails open.
        store.hasContainmentRecord().shouldBeFalse()
        store.claimExit(fence.id).shouldBeFalse()
    }

    @Test
    fun liveFixOnFreshInputs_expectGuardArmedAndGenuineExitClaimable() = runTest {
        repository.refreshFromLiveFix(latitude = 0.0, longitude = 0.0)

        // Discriminator: SKIP registers nothing. Without this the assertions below also pass on a
        // LOCAL pass, which seeds through a different code path entirely.
        coVerify(exactly = 0) { manager.replaceGeofences(any(), any()) }
        store.getEnteredIds() shouldContainSame setOf(fence.id)
        store.hasContainmentRecord().shouldBeTrue()
        // The guarantee the receiver depends on: a genuine departure is matched, not read as phantom.
        store.claimExit(fence.id).shouldBeTrue()
    }

    @Test
    fun liveFixOutsideEveryFence_expectRecordCreatedEmpty() = runTest {
        // ~555m out: a real reading of "inside nothing" must still end the grace.
        repository.refreshFromLiveFix(latitude = 0.005, longitude = 0.0)

        store.getEnteredIds() shouldBeEqualTo emptySet()
        store.hasContainmentRecord().shouldBeTrue()
    }

    @Test
    fun exitClaimedBeforeTheFix_expectTheFixReseeds() = runTest {
        // The epoch contract, which GeofenceRepositoryTest's hand-written stub does not model. A
        // departure that predates the fix is older evidence, so the fix re-seeds; only one claimed
        // after the epoch this pass captured would outrank it (covered in GeofenceRegionStoreTest).
        store.recordEntered(fence.id)
        store.claimExit(fence.id).shouldBeTrue()

        repository.refreshFromLiveFix(latitude = 0.0, longitude = 0.0)

        store.getEnteredIds() shouldContainSame setOf(fence.id)
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
