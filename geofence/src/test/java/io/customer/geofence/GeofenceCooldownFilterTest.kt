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
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
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
    fun cooldown_givenNoPreviousEmit_expectAllowedAndRecorded() {
        every { mockStore.getLastEmitTimestamp(any(), any(), any()) } returns null
        every { mockClock.currentTimeMillis() } returns 100_000L

        filter.suppressedForSeconds("user-1", "biz-1", Event.GeofenceTransition.ENTER).shouldBeNull()
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

        filter.suppressedForSeconds("user-1", "biz-1", Event.GeofenceTransition.ENTER).shouldBeNull()
        filter.record("user-1", "biz-1", Event.GeofenceTransition.ENTER)

        verify(exactly = 1) { mockStore.pruneOlderThan(now - GeofenceConstants.MAX_DUPLICATE_EVENTS_EXPIRY_MS) }
    }

    @Test
    fun cooldown_givenSuppressedEmit_expectNoPruneSweep() {
        val lastEmit = 100_000L
        every { mockStore.getLastEmitTimestamp("user-1", "biz-1", Event.GeofenceTransition.ENTER) } returns lastEmit
        every { mockClock.currentTimeMillis() } returns lastEmit + 1

        filter.suppressedForSeconds("user-1", "biz-1", Event.GeofenceTransition.ENTER).shouldNotBeNull()

        verify(exactly = 0) { mockStore.pruneOlderThan(any()) }
    }

    @Test
    fun cooldown_givenPreviousEmitWithinCooldown_expectRemainderReportedAndNotRecorded() {
        val lastEmit = 100_000L
        val now = lastEmit + (GeofenceConstants.DEDUPE_COOLDOWN_MS - 1)
        every { mockStore.getLastEmitTimestamp("user-1", "biz-1", Event.GeofenceTransition.ENTER) } returns lastEmit
        every { mockClock.currentTimeMillis() } returns now

        filter.suppressedForSeconds("user-1", "biz-1", Event.GeofenceTransition.ENTER).shouldNotBeNull()
        verify(exactly = 0) { mockStore.recordEmit(any(), any(), any(), any()) }
    }

    @Test
    fun suppressedForSeconds_givenHalfTheWindowElapsed_expectTheRemainderInSeconds() {
        // The number goes straight into the suppression log tail, so a constant would read as a
        // real measurement to anyone parsing it.
        val lastEmit = 100_000L
        val elapsed = GeofenceConstants.DEDUPE_COOLDOWN_MS / 2
        every { mockStore.getLastEmitTimestamp("user-1", "biz-1", Event.GeofenceTransition.ENTER) } returns lastEmit
        every { mockClock.currentTimeMillis() } returns lastEmit + elapsed

        filter.suppressedForSeconds("user-1", "biz-1", Event.GeofenceTransition.ENTER) shouldBeEqualTo
            (GeofenceConstants.DEDUPE_COOLDOWN_MS - elapsed) / 1000.0
    }

    @Test
    fun cooldown_givenPreviousEmitExactlyAtCooldownBoundary_expectAllowedAndRecorded() {
        val lastEmit = 100_000L
        val now = lastEmit + GeofenceConstants.DEDUPE_COOLDOWN_MS
        every { mockStore.getLastEmitTimestamp("user-1", "biz-1", Event.GeofenceTransition.ENTER) } returns lastEmit
        every { mockClock.currentTimeMillis() } returns now

        filter.suppressedForSeconds("user-1", "biz-1", Event.GeofenceTransition.ENTER).shouldBeNull()
        filter.record("user-1", "biz-1", Event.GeofenceTransition.ENTER)
        verify(exactly = 1) { mockStore.recordEmit("user-1", "biz-1", Event.GeofenceTransition.ENTER, now) }
    }

    @Test
    fun cooldown_givenPreviousEmitOutsideCooldown_expectAllowedAndRecorded() {
        val lastEmit = 100_000L
        val now = lastEmit + GeofenceConstants.DEDUPE_COOLDOWN_MS + 1
        every { mockStore.getLastEmitTimestamp("user-1", "biz-1", Event.GeofenceTransition.ENTER) } returns lastEmit
        every { mockClock.currentTimeMillis() } returns now

        filter.suppressedForSeconds("user-1", "biz-1", Event.GeofenceTransition.ENTER).shouldBeNull()
        filter.record("user-1", "biz-1", Event.GeofenceTransition.ENTER)
        verify(exactly = 1) { mockStore.recordEmit("user-1", "biz-1", Event.GeofenceTransition.ENTER, now) }
    }

    @Test
    fun cooldown_givenSameGeofenceDifferentTransition_keysIndependently() {
        // ENTER fired recently, EXIT never fired — EXIT should still acquire
        every { mockStore.getLastEmitTimestamp("user-1", "biz-1", Event.GeofenceTransition.ENTER) } returns 100L
        every { mockStore.getLastEmitTimestamp("user-1", "biz-1", Event.GeofenceTransition.EXIT) } returns null
        every { mockClock.currentTimeMillis() } returns 200L

        filter.suppressedForSeconds("user-1", "biz-1", Event.GeofenceTransition.ENTER).shouldNotBeNull()
        filter.suppressedForSeconds("user-1", "biz-1", Event.GeofenceTransition.EXIT).shouldBeNull()
        filter.record("user-1", "biz-1", Event.GeofenceTransition.EXIT)
    }

    @Test
    fun cooldown_givenSameGeofenceDifferentUser_keysIndependently() {
        // Account switch: the previous user's window must not mask the new user's
        // transition on the same fence.
        every { mockStore.getLastEmitTimestamp("user-1", "biz-1", Event.GeofenceTransition.ENTER) } returns 100L
        every { mockStore.getLastEmitTimestamp("user-2", "biz-1", Event.GeofenceTransition.ENTER) } returns null
        every { mockClock.currentTimeMillis() } returns 200L

        filter.suppressedForSeconds("user-1", "biz-1", Event.GeofenceTransition.ENTER).shouldNotBeNull()
        filter.suppressedForSeconds("user-2", "biz-1", Event.GeofenceTransition.ENTER).shouldBeNull()
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

        // Inside the server window but well outside the constant fallback → must block, and the
        // remainder must be measured against the server window rather than the fallback constant.
        every { mockClock.currentTimeMillis() } returns lastEmit + 1_000
        filter.suppressedForSeconds("user-1", "biz-1", Event.GeofenceTransition.ENTER) shouldBeEqualTo
            (serverCooldownMs - 1_000) / 1000.0

        // Past the server window but still inside the constant fallback → must allow.
        every { mockClock.currentTimeMillis() } returns lastEmit + serverCooldownMs + 1
        filter.suppressedForSeconds("user-1", "biz-1", Event.GeofenceTransition.ENTER).shouldBeNull()
        filter.record("user-1", "biz-1", Event.GeofenceTransition.ENTER)
    }

    @Test
    fun clearAll_expectStoreCleared() {
        filter.clearAll()
        verify(exactly = 1) { mockStore.clearAll() }
    }
}
