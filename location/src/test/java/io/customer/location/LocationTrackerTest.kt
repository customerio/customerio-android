package io.customer.location

import io.customer.location.store.LocationPreferenceStore
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.communication.LocationCache
import io.customer.sdk.core.util.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LocationTrackerTest {

    private val locationCache: LocationCache = mockk(relaxUnitFun = true)
    private val store: LocationPreferenceStore = mockk(relaxUnitFun = true)
    private val logger: Logger = mockk(relaxUnitFun = true)
    private val eventBus: EventBus = mockk(relaxUnitFun = true)

    private lateinit var tracker: LocationTracker

    @BeforeEach
    fun setup() {
        tracker = LocationTracker(locationCache, store, logger, eventBus)
    }

    // -- onLocationReceived --

    @Test
    fun givenLocationReceived_expectCachesInPlugin() {
        val location = Event.LocationData(37.7749, -122.4194)

        tracker.onLocationReceived(location)

        verify { locationCache.lastLocation = location }
    }

    @Test
    fun givenLocationReceived_expectPersistsToStore() {
        val location = Event.LocationData(37.7749, -122.4194)

        tracker.onLocationReceived(location)

        verify { store.saveCachedLocation(37.7749, -122.4194) }
    }

    @Test
    fun givenLocationReceived_expectPublishesTrackLocationEvent() {
        val location = Event.LocationData(37.7749, -122.4194)

        tracker.onLocationReceived(location)

        val eventSlot = slot<Event.TrackLocationEvent>()
        verify { eventBus.publish(capture(eventSlot)) }
        eventSlot.captured.location shouldBeEqualTo location
    }

    @Test
    fun givenLocationReceived_expectAlwaysPublishes() {
        // Every call should publish, no filtering
        tracker.onLocationReceived(Event.LocationData(37.7749, -122.4194))
        tracker.onLocationReceived(Event.LocationData(37.7750, -122.4195))
        tracker.onLocationReceived(Event.LocationData(37.7751, -122.4196))

        verify(exactly = 3) { eventBus.publish(any<Event.TrackLocationEvent>()) }
    }

    // -- syncCachedLocationIfNeeded --

    @Test
    fun givenCachedLocationExists_expectPublishesTrackLocationEvent() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns -122.4194

        tracker.syncCachedLocationIfNeeded()

        val eventSlot = slot<Event.TrackLocationEvent>()
        verify { eventBus.publish(capture(eventSlot)) }
        eventSlot.captured.location.latitude shouldBeEqualTo 37.7749
        eventSlot.captured.location.longitude shouldBeEqualTo -122.4194
    }

    @Test
    fun givenNoCachedLatitude_expectNoEvent() {
        every { store.getCachedLatitude() } returns null
        every { store.getCachedLongitude() } returns -122.4194

        tracker.syncCachedLocationIfNeeded()

        verify(exactly = 0) { eventBus.publish(any()) }
    }

    @Test
    fun givenNoCachedLongitude_expectNoEvent() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns null

        tracker.syncCachedLocationIfNeeded()

        verify(exactly = 0) { eventBus.publish(any()) }
    }

    // -- restorePersistedLocation --

    @Test
    fun givenPersistedLocation_expectSetsLocationCache() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns -122.4194

        tracker.restorePersistedLocation()

        val locationSlot = slot<Event.LocationData>()
        verify { locationCache.lastLocation = capture(locationSlot) }
        locationSlot.captured.latitude shouldBeEqualTo 37.7749
        locationSlot.captured.longitude shouldBeEqualTo -122.4194
    }

    @Test
    fun givenNoPersistedLatitude_expectNoOp() {
        every { store.getCachedLatitude() } returns null

        tracker.restorePersistedLocation()

        verify(exactly = 0) { locationCache.lastLocation = any() }
    }

    @Test
    fun givenNoPersistedLongitude_expectNoOp() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns null

        tracker.restorePersistedLocation()

        verify(exactly = 0) { locationCache.lastLocation = any() }
    }

    @Test
    fun givenNullLocationCache_expectNoException() {
        val trackerWithNullCache = LocationTracker(null, store, logger, eventBus)
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns -122.4194

        // Should not throw
        trackerWithNullCache.restorePersistedLocation()
    }

    // -- clearCachedLocation --

    @Test
    fun clearCachedLocation_expectClearsStore() {
        tracker.clearCachedLocation()

        verify { store.clearCachedLocation() }
    }

    @Test
    fun givenNullLocationCache_onLocationReceived_expectStillPersistsAndPublishes() {
        val trackerWithNullCache = LocationTracker(null, store, logger, eventBus)
        val location = Event.LocationData(37.7749, -122.4194)

        trackerWithNullCache.onLocationReceived(location)

        // Cache update is skipped (null), but persist and publish must still happen
        verify { store.saveCachedLocation(37.7749, -122.4194) }
        val eventSlot = slot<Event.TrackLocationEvent>()
        verify { eventBus.publish(capture(eventSlot)) }
        eventSlot.captured.location shouldBeEqualTo location
    }
}
