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
    fun cooldown_givenNoPreviousEmit_expectTrueAndRecorded() {
        every { mockStore.getLastEmitTimestamp(any(), any(), any()) } returns null
        every { mockClock.currentTimeMillis() } returns 100_000L

        filter.isAllowed("user-1", "biz-1", Event.GeofenceTransition.ENTER).shouldBeTrue()
        filter.record("user-1", "biz-1", Event.GeofenceTransition.ENTER)
        verify(exactly = 1) { mockStore.recordEmit("user-1", "biz-1", Event.GeofenceTransition.ENTER, 100_000L) }
    }

    @Test
    fun cooldown_givenAllowedEmit_expectStalePruneSweep() {
        // Each allowed emit sweeps entries older than the max clampable cooldown —
        // they can't suppress under any config, so pruning them bounds the store
        // as fence definitions churn.
        every { mockStore.getLastEmitTimestamp(any(), any(), any()) } returns null
        val now = 100_000L + GeofenceConstants.MAX_DUPLICATE_EVENTS_EXPIRY_MS
        every { mockClock.currentTimeMillis() } returns now

        filter.isAllowed("user-1", "biz-1", Event.GeofenceTransition.ENTER).shouldBeTrue()
        filter.record("user-1", "biz-1", Event.GeofenceTransition.ENTER)

        verify(exactly = 1) { mockStore.pruneOlderThan(now - GeofenceConstants.MAX_DUPLICATE_EVENTS_EXPIRY_MS) }
    }

    @Test
    fun cooldown_givenSuppressedEmit_expectNoPruneSweep() {
        val lastEmit = 100_000L
        every { mockStore.getLastEmitTimestamp("user-1", "biz-1", Event.GeofenceTransition.ENTER) } returns lastEmit
        every { mockClock.currentTimeMillis() } returns lastEmit + 1

        filter.isAllowed("user-1", "biz-1", Event.GeofenceTransition.ENTER).shouldBeFalse()

        verify(exactly = 0) { mockStore.pruneOlderThan(any()) }
    }

    @Test
    fun cooldown_givenPreviousEmitWithinCooldown_expectFalseAndNotRecorded() {
        val lastEmit = 100_000L
        val now = lastEmit + (GeofenceConstants.DEDUPE_COOLDOWN_MS - 1)
        every { mockStore.getLastEmitTimestamp("user-1", "biz-1", Event.GeofenceTransition.ENTER) } returns lastEmit
        every { mockClock.currentTimeMillis() } returns now

        filter.isAllowed("user-1", "biz-1", Event.GeofenceTransition.ENTER).shouldBeFalse()
        verify(exactly = 0) { mockStore.recordEmit(any(), any(), any(), any()) }
    }

    @Test
    fun cooldown_givenPreviousEmitExactlyAtCooldownBoundary_expectTrueAndRecorded() {
        val lastEmit = 100_000L
        val now = lastEmit + GeofenceConstants.DEDUPE_COOLDOWN_MS
        every { mockStore.getLastEmitTimestamp("user-1", "biz-1", Event.GeofenceTransition.ENTER) } returns lastEmit
        every { mockClock.currentTimeMillis() } returns now

        filter.isAllowed("user-1", "biz-1", Event.GeofenceTransition.ENTER).shouldBeTrue()
        filter.record("user-1", "biz-1", Event.GeofenceTransition.ENTER)
        verify(exactly = 1) { mockStore.recordEmit("user-1", "biz-1", Event.GeofenceTransition.ENTER, now) }
    }

    @Test
    fun cooldown_givenPreviousEmitOutsideCooldown_expectTrueAndRecorded() {
        val lastEmit = 100_000L
        val now = lastEmit + GeofenceConstants.DEDUPE_COOLDOWN_MS + 1
        every { mockStore.getLastEmitTimestamp("user-1", "biz-1", Event.GeofenceTransition.ENTER) } returns lastEmit
        every { mockClock.currentTimeMillis() } returns now

        filter.isAllowed("user-1", "biz-1", Event.GeofenceTransition.ENTER).shouldBeTrue()
        filter.record("user-1", "biz-1", Event.GeofenceTransition.ENTER)
        verify(exactly = 1) { mockStore.recordEmit("user-1", "biz-1", Event.GeofenceTransition.ENTER, now) }
    }

    @Test
    fun cooldown_givenSameGeofenceDifferentTransition_keysIndependently() {
        // ENTER fired recently, EXIT never fired — EXIT should still acquire
        every { mockStore.getLastEmitTimestamp("user-1", "biz-1", Event.GeofenceTransition.ENTER) } returns 100L
        every { mockStore.getLastEmitTimestamp("user-1", "biz-1", Event.GeofenceTransition.EXIT) } returns null
        every { mockClock.currentTimeMillis() } returns 200L

        filter.isAllowed("user-1", "biz-1", Event.GeofenceTransition.ENTER).shouldBeFalse()
        filter.isAllowed("user-1", "biz-1", Event.GeofenceTransition.EXIT).shouldBeTrue()
        filter.record("user-1", "biz-1", Event.GeofenceTransition.EXIT)
    }

    @Test
    fun cooldown_givenSameGeofenceDifferentUser_keysIndependently() {
        // Account switch: the previous user's window must not mask the new user's
        // transition on the same fence.
        every { mockStore.getLastEmitTimestamp("user-1", "biz-1", Event.GeofenceTransition.ENTER) } returns 100L
        every { mockStore.getLastEmitTimestamp("user-2", "biz-1", Event.GeofenceTransition.ENTER) } returns null
        every { mockClock.currentTimeMillis() } returns 200L

        filter.isAllowed("user-1", "biz-1", Event.GeofenceTransition.ENTER).shouldBeFalse()
        filter.isAllowed("user-2", "biz-1", Event.GeofenceTransition.ENTER).shouldBeTrue()
        filter.record("user-2", "biz-1", Event.GeofenceTransition.ENTER)
    }

    @Test
    fun cooldown_givenCachedConfig_expectServerConfiguredCooldownUsed() {
        // Server-pushed cooldown is shorter than the fallback. Verifies the
        // filter actually consults GeofenceConfig and isn't pinned to the constant.
        val serverCooldownMs = 5_000L
        every { mockRegionStore.getCachedConfig() } returns GeofenceConfig.fallback().copy(
            duplicateEventsExpiry = serverCooldownMs
        )
        val lastEmit = 100_000L
        every { mockStore.getLastEmitTimestamp("user-1", "biz-1", Event.GeofenceTransition.ENTER) } returns lastEmit

        // Inside the server window but well outside the constant fallback → must block.
        every { mockClock.currentTimeMillis() } returns lastEmit + serverCooldownMs - 1
        filter.isAllowed("user-1", "biz-1", Event.GeofenceTransition.ENTER).shouldBeFalse()

        // Past the server window but still inside the constant fallback → must allow.
        every { mockClock.currentTimeMillis() } returns lastEmit + serverCooldownMs + 1
        filter.isAllowed("user-1", "biz-1", Event.GeofenceTransition.ENTER).shouldBeTrue()
        filter.record("user-1", "biz-1", Event.GeofenceTransition.ENTER)
    }

    @Test
    fun clearAll_expectStoreCleared() {
        filter.clearAll()
        verify(exactly = 1) { mockStore.clearAll() }
    }
}
