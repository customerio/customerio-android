package io.customer.location

import io.customer.location.store.LocationPreferenceStore
import io.customer.location.sync.LocationSyncFilter
import io.customer.sdk.core.pipeline.DataPipeline
import io.customer.sdk.core.util.Logger
import io.customer.sdk.util.EventNames
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LocationSyncCoordinatorTest {

    private val dataPipeline: DataPipeline = mockk(relaxUnitFun = true)
    private val store: LocationPreferenceStore = mockk(relaxUnitFun = true)
    private val syncFilter: LocationSyncFilter = mockk(relaxUnitFun = true)
    private val enrichmentProvider: LocationEnrichmentProvider = mockk(relaxUnitFun = true)
    private val logger: Logger = mockk(relaxUnitFun = true)

    private lateinit var coordinator: LocationSyncCoordinator

    @BeforeEach
    fun setup() {
        every { dataPipeline.isUserIdentified } returns true
        every { syncFilter.filterAndRecord(any(), any()) } returns true
        coordinator = LocationSyncCoordinator(dataPipeline, store, syncFilter, enrichmentProvider, logger)
    }

    // -- onLocationReceived --

    @Test
    fun givenLocationReceived_expectPersistsToStore() {
        coordinator.onLocationReceived(37.7749, -122.4194)

        verify { store.saveCachedLocation(37.7749, -122.4194) }
    }

    @Test
    fun givenLocationReceived_expectUpdatesEnrichmentProvider() {
        coordinator.onLocationReceived(37.7749, -122.4194)

        verify { enrichmentProvider.updateLocation(LocationCoordinates(37.7749, -122.4194)) }
    }

    @Test
    fun givenLocationReceived_userIdentified_filterPasses_expectTrackCalled() {
        coordinator.onLocationReceived(37.7749, -122.4194)

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

        coordinator.onLocationReceived(37.7749, -122.4194)

        verify(exactly = 0) { dataPipeline.track(any(), any()) }
    }

    @Test
    fun givenLocationReceived_filterRejects_expectTrackNotCalled() {
        every { syncFilter.filterAndRecord(any(), any()) } returns false

        coordinator.onLocationReceived(37.7749, -122.4194)

        verify(exactly = 0) { dataPipeline.track(any(), any()) }
    }

    @Test
    fun givenNullDataPipeline_expectNoException() {
        val coordinatorWithNullPipeline = LocationSyncCoordinator(null, store, syncFilter, enrichmentProvider, logger)

        coordinatorWithNullPipeline.onLocationReceived(37.7749, -122.4194)

        // Persist and enrichment still happen, but no track call
        verify { store.saveCachedLocation(37.7749, -122.4194) }
        verify { enrichmentProvider.updateLocation(LocationCoordinates(37.7749, -122.4194)) }
    }

    // -- syncCachedLocationIfNeeded --

    @Test
    fun givenCachedLocationExists_expectTriesSendLocationTrack() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns -122.4194

        coordinator.syncCachedLocationIfNeeded()

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

        coordinator.syncCachedLocationIfNeeded()

        verify(exactly = 0) { dataPipeline.track(any(), any()) }
    }

    @Test
    fun givenNoCachedLongitude_expectNoTrack() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns null

        coordinator.syncCachedLocationIfNeeded()

        verify(exactly = 0) { dataPipeline.track(any(), any()) }
    }

    // -- onUserIdentified --

    @Test
    fun givenUserIdentified_withCachedLocation_expectSyncsCachedLocation() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns -122.4194

        coordinator.onUserIdentified()

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

        coordinator.onUserIdentified()

        verify { syncFilter.filterAndRecord(37.7749, -122.4194) }
    }

    @Test
    fun givenUserIdentified_filterRejects_expectNoTrack() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns -122.4194
        every { syncFilter.filterAndRecord(any(), any()) } returns false

        coordinator.onUserIdentified()

        verify(exactly = 0) { dataPipeline.track(any(), any()) }
    }

    @Test
    fun givenUserIdentified_noCachedLocation_expectNoTrack() {
        every { store.getCachedLatitude() } returns null

        coordinator.onUserIdentified()

        verify(exactly = 0) { dataPipeline.track(any(), any()) }
    }

    // -- resetContext --

    @Test
    fun givenResetContext_expectClearsSyncFilter() {
        coordinator.resetContext()

        verify { syncFilter.clearSyncedData() }
    }
}
