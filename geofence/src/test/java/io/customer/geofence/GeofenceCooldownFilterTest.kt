package io.customer.geofence

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.store.GeofenceCooldownStore
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Clock
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceCooldownFilterTest : RobolectricTest() {

    private val mockStore: GeofenceCooldownStore = mockk(relaxed = true)
    private val mockRegionStore: GeofenceRegionStore = mockk(relaxed = true) {
        // Default to no cached config — exercises the fallback to DEDUPE_COOLDOWN_MS.
        every { getCachedConfig() } returns null
    }
    private val mockClock: Clock = mockk(relaxed = true)

    private lateinit var filter: GeofenceCooldownFilter

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { })
        filter = GeofenceCooldownFilter(mockStore, mockRegionStore, mockClock)
    }

    @Test
    fun tryAcquire_givenNoPreviousEmit_expectTrueAndRecorded() {
        every { mockStore.getLastEmitTimestamp(any(), any()) } returns null
        every { mockClock.currentTimeMillis() } returns 100_000L

        filter.tryAcquire("biz-1", Event.GeofenceTransition.ENTER).shouldBeTrue()
        verify(exactly = 1) { mockStore.recordEmit("biz-1", Event.GeofenceTransition.ENTER, 100_000L) }
    }

    @Test
    fun tryAcquire_givenPreviousEmitWithinCooldown_expectFalseAndNotRecorded() {
        val lastEmit = 100_000L
        val now = lastEmit + (GeofenceConstants.DEDUPE_COOLDOWN_MS - 1)
        every { mockStore.getLastEmitTimestamp("biz-1", Event.GeofenceTransition.ENTER) } returns lastEmit
        every { mockClock.currentTimeMillis() } returns now

        filter.tryAcquire("biz-1", Event.GeofenceTransition.ENTER).shouldBeFalse()
        verify(exactly = 0) { mockStore.recordEmit(any(), any(), any()) }
    }

    @Test
    fun tryAcquire_givenPreviousEmitExactlyAtCooldownBoundary_expectTrueAndRecorded() {
        val lastEmit = 100_000L
        val now = lastEmit + GeofenceConstants.DEDUPE_COOLDOWN_MS
        every { mockStore.getLastEmitTimestamp("biz-1", Event.GeofenceTransition.ENTER) } returns lastEmit
        every { mockClock.currentTimeMillis() } returns now

        filter.tryAcquire("biz-1", Event.GeofenceTransition.ENTER).shouldBeTrue()
        verify(exactly = 1) { mockStore.recordEmit("biz-1", Event.GeofenceTransition.ENTER, now) }
    }

    @Test
    fun tryAcquire_givenPreviousEmitOutsideCooldown_expectTrueAndRecorded() {
        val lastEmit = 100_000L
        val now = lastEmit + GeofenceConstants.DEDUPE_COOLDOWN_MS + 1
        every { mockStore.getLastEmitTimestamp("biz-1", Event.GeofenceTransition.ENTER) } returns lastEmit
        every { mockClock.currentTimeMillis() } returns now

        filter.tryAcquire("biz-1", Event.GeofenceTransition.ENTER).shouldBeTrue()
        verify(exactly = 1) { mockStore.recordEmit("biz-1", Event.GeofenceTransition.ENTER, now) }
    }

    @Test
    fun tryAcquire_givenSameGeofenceDifferentTransition_keysIndependently() {
        // ENTER fired recently, EXIT never fired — EXIT should still acquire
        every { mockStore.getLastEmitTimestamp("biz-1", Event.GeofenceTransition.ENTER) } returns 100L
        every { mockStore.getLastEmitTimestamp("biz-1", Event.GeofenceTransition.EXIT) } returns null
        every { mockClock.currentTimeMillis() } returns 200L

        filter.tryAcquire("biz-1", Event.GeofenceTransition.ENTER).shouldBeFalse()
        filter.tryAcquire("biz-1", Event.GeofenceTransition.EXIT).shouldBeTrue()
    }

    @Test
    fun tryAcquire_givenCachedConfig_expectServerConfiguredCooldownUsed() {
        // Server-pushed cooldown is shorter than the fallback. Verifies the
        // filter actually consults GeofenceConfig and isn't pinned to the constant.
        val serverCooldownMs = 5_000L
        every { mockRegionStore.getCachedConfig() } returns GeofenceConfig.fallback().copy(
            duplicateEventsExpiry = serverCooldownMs
        )
        val lastEmit = 100_000L
        every { mockStore.getLastEmitTimestamp("biz-1", Event.GeofenceTransition.ENTER) } returns lastEmit

        // Inside the server window but well outside the constant fallback → must block.
        every { mockClock.currentTimeMillis() } returns lastEmit + serverCooldownMs - 1
        filter.tryAcquire("biz-1", Event.GeofenceTransition.ENTER).shouldBeFalse()

        // Past the server window but still inside the constant fallback → must allow.
        every { mockClock.currentTimeMillis() } returns lastEmit + serverCooldownMs + 1
        filter.tryAcquire("biz-1", Event.GeofenceTransition.ENTER).shouldBeTrue()
    }

    @Test
    fun clearAll_expectStoreCleared() {
        filter.clearAll()
        verify(exactly = 1) { mockStore.clearAll() }
    }
}
