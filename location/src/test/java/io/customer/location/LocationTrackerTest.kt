package io.customer.location

import io.customer.location.store.LocationPreferenceStore
import io.customer.location.sync.LocationSyncFilter
import io.customer.sdk.core.pipeline.DataPipeline
import io.customer.sdk.core.util.Logger
import io.customer.sdk.util.EventNames
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEmpty
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
        every { dataPipeline.isUserIdentified } returns true
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
        every { dataPipeline.isUserIdentified } returns false

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

        val context = tracker.getIdentifyContext()
        context.shouldNotBeEmpty()
        context["location_latitude"] shouldBeEqualTo 37.7749
        context["location_longitude"] shouldBeEqualTo -122.4194
    }

    @Test
    fun givenNoPersistedLatitude_expectNoContext() {
        every { store.getCachedLatitude() } returns null

        tracker.restorePersistedLocation()

        tracker.getIdentifyContext().shouldBeEmpty()
    }

    @Test
    fun givenNoPersistedLongitude_expectNoContext() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns null

        tracker.restorePersistedLocation()

        tracker.getIdentifyContext().shouldBeEmpty()
    }

    // -- getIdentifyContext --

    @Test
    fun givenNoLocation_expectReturnsEmptyMap() {
        tracker.getIdentifyContext().shouldBeEmpty()
    }

    @Test
    fun givenLocationReceived_expectReturnsLocationContext() {
        tracker.onLocationReceived(37.7749, -122.4194)

        val context = tracker.getIdentifyContext()
        context.shouldNotBeEmpty()
        context["location_latitude"] shouldBeEqualTo 37.7749
        context["location_longitude"] shouldBeEqualTo -122.4194
    }

    // -- onUserIdentified --

    @Test
    fun givenUserIdentified_withCachedLocation_expectSyncsCachedLocation() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns -122.4194

        tracker.onUserIdentified()

        verify {
            dataPipeline.track(
                name = EventNames.LOCATION_UPDATE,
                properties = mapOf("latitude" to 37.7749, "longitude" to -122.4194)
            )
        }
    }

    @Test
    fun givenUserIdentified_withCachedLocation_expectSyncFilterConsulted() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns -122.4194

        tracker.onUserIdentified()

        verify { syncFilter.filterAndRecord(37.7749, -122.4194) }
    }

    @Test
    fun givenUserIdentified_filterRejects_expectNoTrack() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns -122.4194
        every { syncFilter.filterAndRecord(any(), any()) } returns false

        tracker.onUserIdentified()

        verify(exactly = 0) { dataPipeline.track(any(), any()) }
    }

    @Test
    fun givenUserIdentified_noCachedLocation_expectNoTrack() {
        every { store.getCachedLatitude() } returns null

        tracker.onUserIdentified()

        verify(exactly = 0) { dataPipeline.track(any(), any()) }
    }

    // -- onReset --

    @Test
    fun givenReset_expectClearsEverything() {
        tracker.onLocationReceived(37.7749, -122.4194)

        tracker.onReset()

        verify { store.clearCachedLocation() }
        verify { syncFilter.clearSyncedData() }
        tracker.getIdentifyContext().shouldBeEmpty()
    }
}
