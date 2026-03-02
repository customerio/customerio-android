package io.customer.location

import io.customer.location.store.LocationPreferenceStore
import io.customer.sdk.core.util.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEmpty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LocationEnrichmentProviderTest {

    private val store: LocationPreferenceStore = mockk(relaxUnitFun = true)
    private val logger: Logger = mockk(relaxUnitFun = true)

    private lateinit var provider: LocationEnrichmentProvider

    @BeforeEach
    fun setup() {
        provider = LocationEnrichmentProvider(store, logger)
    }

    // -- getIdentifyContext --

    @Test
    fun givenNoLocation_expectReturnsEmptyMap() {
        provider.getIdentifyContext().shouldBeEmpty()
    }

    @Test
    fun givenLocationUpdated_expectReturnsLocationContext() {
        provider.updateLocation(LocationCoordinates(37.7749, -122.4194))

        val context = provider.getIdentifyContext()
        context.shouldNotBeEmpty()
        context["location_latitude"] shouldBeEqualTo 37.7749
        context["location_longitude"] shouldBeEqualTo -122.4194
    }

    // -- restorePersistedLocation --

    @Test
    fun givenPersistedLocation_expectSetsInMemoryCache() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns -122.4194

        provider.restorePersistedLocation()

        val context = provider.getIdentifyContext()
        context.shouldNotBeEmpty()
        context["location_latitude"] shouldBeEqualTo 37.7749
        context["location_longitude"] shouldBeEqualTo -122.4194
    }

    @Test
    fun givenNoPersistedLatitude_expectNoContext() {
        every { store.getCachedLatitude() } returns null

        provider.restorePersistedLocation()

        provider.getIdentifyContext().shouldBeEmpty()
    }

    @Test
    fun givenNoPersistedLongitude_expectNoContext() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns null

        provider.restorePersistedLocation()

        provider.getIdentifyContext().shouldBeEmpty()
    }

    // -- resetContext --

    @Test
    fun givenResetContext_expectClearsInMemoryCacheAndStore() {
        provider.updateLocation(LocationCoordinates(37.7749, -122.4194))

        provider.resetContext()

        provider.getIdentifyContext().shouldBeEmpty()
        verify { store.clearCachedLocation() }
    }
}
