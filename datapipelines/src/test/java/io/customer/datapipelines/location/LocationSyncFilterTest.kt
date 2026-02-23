package io.customer.datapipelines.location

import io.customer.datapipelines.store.LocationSyncStore
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [LocationSyncFilter] verifying 24h + 1km filter logic,
 * first-time pass behavior, and reset on profile switch/logout.
 *
 * Uses Robolectric because [LocationSyncFilter.distanceBetween] calls
 * [android.location.Location.distanceBetween] which is a native method
 * requiring a shadow implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocationSyncFilterTest {

    private lateinit var store: FakeLocationSyncStore
    private lateinit var filter: LocationSyncFilter

    @Before
    fun setup() {
        store = FakeLocationSyncStore()
        filter = LocationSyncFilter(store)
    }

    // -- First-time behavior --

    @Test
    fun givenNeverSynced_expectFilterPasses() {
        filter.filterAndRecord(37.7749, -122.4194).shouldBeTrue()
    }

    @Test
    fun givenNeverSynced_expectSyncedDataSaved() {
        filter.filterAndRecord(37.7749, -122.4194)

        store.getSyncedLatitude() shouldBeCloseTo 37.7749
        store.getSyncedLongitude() shouldBeCloseTo -122.4194
        store.getSyncedTimestamp().shouldNotBeNull()
    }

    // -- Time-based filtering (within 24h -> fail before distance check) --

    @Test
    fun givenSyncedJustNow_expectFilterFails() {
        store.saveSyncedLocation(37.7749, -122.4194, System.currentTimeMillis())

        // Even with a completely different location, within 24h -> fail
        filter.filterAndRecord(40.7128, -74.0060).shouldBeFalse()
    }

    @Test
    fun givenSynced23hAgo_expectFilterFails() {
        val twentyThreeHoursAgo = System.currentTimeMillis() - 23 * 60 * 60 * 1000L
        store.saveSyncedLocation(37.7749, -122.4194, twentyThreeHoursAgo)

        filter.filterAndRecord(40.7128, -74.0060).shouldBeFalse()
    }

    // -- Time + distance filtering --

    @Test
    fun givenSynced25hAgo_sameLocation_expectFilterFails() {
        val twentyFiveHoursAgo = System.currentTimeMillis() - 25 * 60 * 60 * 1000L
        store.saveSyncedLocation(37.7749, -122.4194, twentyFiveHoursAgo)

        // Same coordinates -> distance ~ 0 -> below 1km -> fail
        filter.filterAndRecord(37.7749, -122.4194).shouldBeFalse()
    }

    @Test
    fun givenSynced25hAgo_nearbyLocation_expectFilterFails() {
        val twentyFiveHoursAgo = System.currentTimeMillis() - 25 * 60 * 60 * 1000L
        store.saveSyncedLocation(37.7749, -122.4194, twentyFiveHoursAgo)

        // ~11 meters away -> well under 1km
        filter.filterAndRecord(37.7750, -122.4194).shouldBeFalse()
    }

    @Test
    fun givenSynced25hAgo_farLocation_expectFilterPasses() {
        val twentyFiveHoursAgo = System.currentTimeMillis() - 25 * 60 * 60 * 1000L
        store.saveSyncedLocation(37.7749, -122.4194, twentyFiveHoursAgo)

        // SF -> NYC ~ 4,130 km -> well over 1km
        filter.filterAndRecord(40.7128, -74.0060).shouldBeTrue()
    }

    @Test
    fun givenFilterPasses_expectSyncedDataUpdated() {
        val twentyFiveHoursAgo = System.currentTimeMillis() - 25 * 60 * 60 * 1000L
        store.saveSyncedLocation(37.7749, -122.4194, twentyFiveHoursAgo)

        filter.filterAndRecord(40.7128, -74.0060)

        store.getSyncedLatitude() shouldBeCloseTo 40.7128
        store.getSyncedLongitude() shouldBeCloseTo -74.0060
        // Timestamp should be updated to approximately now
        val timeDiff = System.currentTimeMillis() - store.getSyncedTimestamp()!!
        (timeDiff < 1000).shouldBeTrue()
    }

    // -- Rapid sequential calls --

    @Test
    fun givenFirstCallPasses_secondCallImmediately_expectSecondFails() {
        // First call: no synced data -> passes
        filter.filterAndRecord(37.7749, -122.4194).shouldBeTrue()

        // Second call immediately: timestamp just saved -> within 24h -> fails
        filter.filterAndRecord(40.7128, -74.0060).shouldBeFalse()
    }

    // -- Reset behavior --

    @Test
    fun givenClearedAfterSync_expectFilterPasses() {
        store.saveSyncedLocation(37.7749, -122.4194, System.currentTimeMillis())

        filter.clearSyncedData()

        // Should pass as if first time
        filter.filterAndRecord(37.7749, -122.4194).shouldBeTrue()
    }

    @Test
    fun givenClearedAfterSync_expectOldWindowGone() {
        store.saveSyncedLocation(37.7749, -122.4194, System.currentTimeMillis())

        // Without clear: same location within 24h -> would fail
        filter.filterAndRecord(37.7749, -122.4194).shouldBeFalse()

        filter.clearSyncedData()

        // Same location now passes because synced state is gone
        filter.filterAndRecord(37.7749, -122.4194).shouldBeTrue()
    }

    // -- Store not modified on rejection --

    @Test
    fun givenFilterFails_expectSyncedDataNotModified() {
        val originalTimestamp = System.currentTimeMillis()
        store.saveSyncedLocation(37.7749, -122.4194, originalTimestamp)

        // Within 24h, far location -> fails
        filter.filterAndRecord(40.7128, -74.0060).shouldBeFalse()

        // Store must be exactly as it was before the failed call
        store.getSyncedLatitude() shouldBeCloseTo 37.7749
        store.getSyncedLongitude() shouldBeCloseTo -122.4194
        store.getSyncedTimestamp() shouldBeEqualTo originalTimestamp
    }

    // -- Exact boundary --

    @Test
    fun givenSyncedExactly24hAgo_farLocation_expectFilterPasses() {
        // Code uses `< 24h` (strict less-than), so exactly 24h should pass
        val exactly24hAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        store.saveSyncedLocation(37.7749, -122.4194, exactly24hAgo)

        // SF -> NYC, distance clearly > 1km
        filter.filterAndRecord(40.7128, -74.0060).shouldBeTrue()
    }

    // -- Missing synced lat/lng edge cases --

    @Test
    fun givenTimestampExists_butNoLatLng_expectFilterPasses() {
        // Only timestamp, no coordinates (shouldn't happen normally but handle gracefully)
        store.setTimestampOnly(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)

        filter.filterAndRecord(37.7749, -122.4194).shouldBeTrue()
    }

    // -- Clear on empty store --

    @Test
    fun givenNoSyncedData_clearSyncedData_expectNoException() {
        // Clearing an already-empty store must not throw
        filter.clearSyncedData()

        // Still passes as first-time after clear
        filter.filterAndRecord(37.7749, -122.4194).shouldBeTrue()
    }
}

// -- Helpers --

/**
 * In-memory fake for testing [LocationSyncFilter] without SharedPreferences.
 */
internal class FakeLocationSyncStore : LocationSyncStore {
    private var latitude: Double? = null
    private var longitude: Double? = null
    private var timestamp: Long? = null

    override fun saveSyncedLocation(latitude: Double, longitude: Double, timestamp: Long) {
        this.latitude = latitude
        this.longitude = longitude
        this.timestamp = timestamp
    }

    override fun getSyncedLatitude(): Double? = latitude
    override fun getSyncedLongitude(): Double? = longitude
    override fun getSyncedTimestamp(): Long? = timestamp

    override fun clearSyncedData() {
        latitude = null
        longitude = null
        timestamp = null
    }

    /** Sets only the timestamp without coordinates (edge case testing). */
    fun setTimestampOnly(timestamp: Long) {
        this.timestamp = timestamp
        this.latitude = null
        this.longitude = null
    }
}

private infix fun Double?.shouldBeCloseTo(expected: Double) {
    if (this == null) throw AssertionError("Expected $expected but was null")
    val diff = kotlin.math.abs(this - expected)
    if (diff > 0.0001) throw AssertionError("Expected $expected but was $this (diff=$diff)")
}

private fun Long?.shouldNotBeNull() {
    if (this == null) throw AssertionError("Expected non-null but was null")
}
