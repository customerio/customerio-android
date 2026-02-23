package io.customer.location

import io.customer.location.store.LocationPreferenceStore
import io.customer.location.sync.LocationSyncFilter
import io.customer.sdk.core.pipeline.DataPipeline
import io.customer.sdk.core.util.Logger
import io.customer.sdk.util.EventNames
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LocationTrackerTest {

    private val dataPipeline: DataPipeline = mockk(relaxUnitFun = true)
    private val store: LocationPreferenceStore = mockk(relaxUnitFun = true)
    private val syncFilter: LocationSyncFilter = mockk(relaxUnitFun = true)
    private val logger: Logger = mockk(relaxUnitFun = true)

    private lateinit var tracker: LocationTracker

    @BeforeEach
    fun setup() {
        every { dataPipeline.userId } returns "user-123"
        every { syncFilter.filterAndRecord(any(), any()) } returns true
        tracker = LocationTracker(dataPipeline, store, syncFilter, logger)
    }

    // -- onLocationReceived --

    @Test
    fun givenLocationReceived_expectPersistsToStore() {
        tracker.onLocationReceived(37.7749, -122.4194)

        verify { store.saveCachedLocation(37.7749, -122.4194) }
    }

    @Test
    fun givenLocationReceived_userIdentified_filterPasses_expectTrackCalled() {
        tracker.onLocationReceived(37.7749, -122.4194)

        verify {
            dataPipeline.track(
                name = EventNames.LOCATION_UPDATE,
                properties = mapOf("latitude" to 37.7749, "longitude" to -122.4194)
            )
        }
    }

    @Test
    fun givenLocationReceived_noUserId_expectTrackNotCalled() {
        every { dataPipeline.userId } returns null

        tracker.onLocationReceived(37.7749, -122.4194)

        verify(exactly = 0) { dataPipeline.track(any(), any()) }
    }

    @Test
    fun givenLocationReceived_emptyUserId_expectTrackNotCalled() {
        every { dataPipeline.userId } returns ""

        tracker.onLocationReceived(37.7749, -122.4194)

        verify(exactly = 0) { dataPipeline.track(any(), any()) }
    }

    @Test
    fun givenLocationReceived_filterRejects_expectTrackNotCalled() {
        every { syncFilter.filterAndRecord(any(), any()) } returns false

        tracker.onLocationReceived(37.7749, -122.4194)

        verify(exactly = 0) { dataPipeline.track(any(), any()) }
    }

    @Test
    fun givenNullDataPipeline_expectNoException() {
        val trackerWithNullPipeline = LocationTracker(null, store, syncFilter, logger)

        trackerWithNullPipeline.onLocationReceived(37.7749, -122.4194)

        // Persist still happens, but no track call
        verify { store.saveCachedLocation(37.7749, -122.4194) }
    }

    // -- syncCachedLocationIfNeeded --

    @Test
    fun givenCachedLocationExists_expectTriesSendLocationTrack() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns -122.4194

        tracker.syncCachedLocationIfNeeded()

        verify {
            dataPipeline.track(
                name = EventNames.LOCATION_UPDATE,
                properties = mapOf("latitude" to 37.7749, "longitude" to -122.4194)
            )
        }
    }

    @Test
    fun givenNoCachedLatitude_expectNoTrack() {
        every { store.getCachedLatitude() } returns null
        every { store.getCachedLongitude() } returns -122.4194

        tracker.syncCachedLocationIfNeeded()

        verify(exactly = 0) { dataPipeline.track(any(), any()) }
    }

    @Test
    fun givenNoCachedLongitude_expectNoTrack() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns null

        tracker.syncCachedLocationIfNeeded()

        verify(exactly = 0) { dataPipeline.track(any(), any()) }
    }

    // -- restorePersistedLocation --

    @Test
    fun givenPersistedLocation_expectSetsInMemoryCache() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns -122.4194

        tracker.restorePersistedLocation()

        val attrs = tracker.getProfileEnrichmentAttributes()
        attrs.shouldNotBeNull()
        attrs["location_latitude"] shouldBeEqualTo 37.7749
        attrs["location_longitude"] shouldBeEqualTo -122.4194
    }

    @Test
    fun givenNoPersistedLatitude_expectNoEnrichment() {
        every { store.getCachedLatitude() } returns null

        tracker.restorePersistedLocation()

        tracker.getProfileEnrichmentAttributes().shouldBeNull()
    }

    @Test
    fun givenNoPersistedLongitude_expectNoEnrichment() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns null

        tracker.restorePersistedLocation()

        tracker.getProfileEnrichmentAttributes().shouldBeNull()
    }

    // -- getProfileEnrichmentAttributes --

    @Test
    fun givenNoLocation_expectReturnsNull() {
        tracker.getProfileEnrichmentAttributes().shouldBeNull()
    }

    @Test
    fun givenLocationReceived_expectReturnsLocationMap() {
        tracker.onLocationReceived(37.7749, -122.4194)

        val attrs = tracker.getProfileEnrichmentAttributes()
        attrs.shouldNotBeNull()
        attrs["location_latitude"] shouldBeEqualTo 37.7749
        attrs["location_longitude"] shouldBeEqualTo -122.4194
    }

    // -- onUserChanged --

    @Test
    fun givenProfileSwitch_expectClearsSyncFilter() {
        every { store.getCachedLatitude() } returns null

        // First call sets lastKnownUserId
        tracker.onUserChanged("user-a", "anon-1")

        // Switch to different user
        tracker.onUserChanged("user-b", "anon-1")

        verify { syncFilter.clearSyncedData() }
    }

    @Test
    fun givenFirstIdentify_expectNoClearSyncFilter() {
        every { store.getCachedLatitude() } returns null

        tracker.onUserChanged("user-a", "anon-1")

        // First identify should not clear (no previous user)
        verify(exactly = 0) { syncFilter.clearSyncedData() }
    }

    @Test
    fun givenSameUserIdentify_expectNoClearSyncFilter() {
        every { store.getCachedLatitude() } returns null

        tracker.onUserChanged("user-a", "anon-1")
        tracker.onUserChanged("user-a", "anon-1")

        verify(exactly = 0) { syncFilter.clearSyncedData() }
    }

    @Test
    fun givenUserChangedWithUserId_expectSyncsCachedLocation() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns -122.4194

        tracker.onUserChanged("user-a", "anon-1")

        verify {
            dataPipeline.track(
                name = EventNames.LOCATION_UPDATE,
                properties = mapOf("latitude" to 37.7749, "longitude" to -122.4194)
            )
        }
    }

    @Test
    fun givenUserChangedWithNullUserId_expectNoSync() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns -122.4194

        tracker.onUserChanged(null, "anon-1")

        verify(exactly = 0) { dataPipeline.track(any(), any()) }
    }

    // -- onReset --

    @Test
    fun givenReset_expectClearsEverything() {
        tracker.onLocationReceived(37.7749, -122.4194)

        tracker.onReset()

        verify { store.clearCachedLocation() }
        verify { syncFilter.clearSyncedData() }
        tracker.getProfileEnrichmentAttributes().shouldBeNull()
    }
}
