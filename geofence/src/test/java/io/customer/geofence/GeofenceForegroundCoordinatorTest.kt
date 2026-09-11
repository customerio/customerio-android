package io.customer.geofence

import io.customer.base.internal.InternalCustomerIOApi
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.location.LocationCoordinates
import io.customer.location.LocationServices
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test

@OptIn(InternalCustomerIOApi::class)
class GeofenceForegroundCoordinatorTest {

    private val mockLocationServices: LocationServices = mockk(relaxed = true)
    private val identifiedUserStore: SecureUserStore = mockk {
        every { getUserId() } returns "user-42"
    }

    private val mockServices: GeofenceServices = mockk(relaxed = true)
    private val mockRegionStore: GeofenceRegionStore = mockk(relaxed = true)

    /** The real coordinator, built directly. */
    private fun coordinatorWith(
        mode: GeofenceLocationMode,
        secureUserStore: SecureUserStore = identifiedUserStore,
        lastKnown: LocationCoordinates? = null
    ) = GeofenceForegroundCoordinator(
        services = mockServices,
        secureUserStore = secureUserStore,
        locationServices = mockLocationServices,
        regionStore = mockRegionStore,
        lastKnownLocation = { lastKnown },
        locationMode = mode,
        logger = mockk(relaxed = true)
    )

    // MARK: - onForeground

    @Test
    fun onForeground_givenAutomaticAndIdentified_expectFixTakenAndNoRetry() = runTest {
        coordinatorWith(GeofenceLocationMode.AUTOMATIC).onForeground()

        verifyOrder {
            mockServices.onRefreshRequested()
            mockLocationServices.requestLocationUpdateSilently()
        }
        verify(exactly = 0) { mockServices.onForegroundRetry(any(), any()) }
    }

    @Test
    fun onForeground_givenManual_expectRetryPathInsteadOfTakingAFix() = runTest {
        every { mockServices.isAwaitingLocation() } returns true

        coordinatorWith(GeofenceLocationMode.MANUAL).onForeground()

        verify(exactly = 0) { mockServices.onRefreshRequested() }
        verify { mockServices.onForegroundRetry(any(), any()) }
    }

    @Test
    fun onForeground_givenSyncAwaitingLocation_expectRetryFromAnchor() = runTest {
        every { mockServices.isAwaitingLocation() } returns true
        every { mockRegionStore.getLastMovementTriggerLocation() } returns GeofenceLocation(1.5, 2.5)

        coordinatorWith(GeofenceLocationMode.AUTOMATIC).onForeground()

        verify { mockServices.onForegroundRetry(latitude = 1.5, longitude = 2.5) }
    }

    @Test
    fun onForeground_givenHostRefreshPending_expectLiveFixRequestedNotAnchorSync() = runTest {
        every { mockServices.isAwaitingLocation() } returns true
        every { mockServices.isHostRefreshPending() } returns true

        coordinatorWith(GeofenceLocationMode.AUTOMATIC).onForeground()

        verify { mockLocationServices.requestLocationUpdateSilently() }
        verify(exactly = 0) { mockServices.onForegroundRetry(any(), any()) }
    }

    @Test
    fun onForeground_givenFixLandsDuringTheAnchorRead_expectNoRetry() = runTest {
        // Three reads: takeFixForRefresh's guard, the retry guard, and the post-anchor re-check.
        every { mockServices.isAwaitingLocation() } returnsMany listOf(true, true, false)

        coordinatorWith(GeofenceLocationMode.AUTOMATIC).onForeground()

        verify(exactly = 0) { mockServices.onForegroundRetry(any(), any()) }
    }

    @Test
    fun autoAcquireIfNeeded_givenNoLocationAndAutomatic_expectSilentFetch() {
        coordinatorWith(GeofenceLocationMode.AUTOMATIC).autoAcquireIfNeeded(currentLocation = null)

        verify { mockLocationServices.requestLocationUpdateSilently() }
    }

    @Test
    fun autoAcquireIfNeeded_givenNoLocationAndManual_expectNoFetch() {
        coordinatorWith(GeofenceLocationMode.MANUAL).autoAcquireIfNeeded(currentLocation = null)

        verify(exactly = 0) { mockLocationServices.requestLocationUpdateSilently() }
    }

    @Test
    fun autoAcquireIfNeeded_givenLocationAlreadyAvailable_expectNoFetch() {
        coordinatorWith(GeofenceLocationMode.AUTOMATIC)
            .autoAcquireIfNeeded(LocationCoordinates(latitude = 1.0, longitude = 2.0))

        verify(exactly = 0) { mockLocationServices.requestLocationUpdateSilently() }
    }

    @Test
    fun takeFixForRefresh_givenAutomatic_expectArmedAndSilentFetch() {
        coordinatorWith(GeofenceLocationMode.AUTOMATIC, secureUserStore = identifiedUserStore)
            .takeFixForRefresh() shouldBeEqualTo true

        verifyOrder {
            mockServices.onRefreshRequested()
            mockLocationServices.requestLocationUpdateSilently()
        }
    }

    @Test
    fun takeFixForRefresh_givenManual_expectNoFetchOrArm() {
        coordinatorWith(GeofenceLocationMode.MANUAL, secureUserStore = identifiedUserStore)
            .takeFixForRefresh() shouldBeEqualTo false

        verify(exactly = 0) { mockServices.onRefreshRequested() }
        verify(exactly = 0) { mockLocationServices.requestLocationUpdateSilently() }
    }

    @Test
    fun takeFixForRefresh_givenSyncAlreadyAwaitingLocation_expectDeferredToStuckSyncPath() {
        // Stub the field the coordinator holds; a local mock would be shadowed.
        every { mockServices.isAwaitingLocation() } returns true

        coordinatorWith(GeofenceLocationMode.AUTOMATIC, secureUserStore = identifiedUserStore)
            .takeFixForRefresh() shouldBeEqualTo false

        verify(exactly = 0) { mockServices.onRefreshRequested() }
        verify(exactly = 0) { mockLocationServices.requestLocationUpdateSilently() }
    }

    @Test
    fun takeFixForRefresh_givenNoIdentifiedUser_expectNoFetchOrArm() {
        val signedOutStore: SecureUserStore = mockk {
            every { getUserId() } returns null
        }

        coordinatorWith(GeofenceLocationMode.AUTOMATIC, secureUserStore = signedOutStore)
            .takeFixForRefresh() shouldBeEqualTo false

        verify(exactly = 0) { mockServices.onRefreshRequested() }
        verify(exactly = 0) { mockLocationServices.requestLocationUpdateSilently() }
    }

    @Test
    fun resolveAnchor_givenRegistrationCenter_expectItPreferredOverLastKnown() {
        val anchor = GeofenceForegroundCoordinator.resolveAnchor(
            registrationCenter = GeofenceLocation(latitude = 10.0, longitude = 20.0),
            lastKnown = LocationCoordinates(latitude = 1.0, longitude = 2.0)
        )

        anchor shouldBeEqualTo LocationCoordinates(latitude = 10.0, longitude = 20.0)
    }

    @Test
    fun resolveAnchor_givenNoRegistrationCenter_expectFallsBackToLastKnown() {
        val anchor = GeofenceForegroundCoordinator.resolveAnchor(
            registrationCenter = null,
            lastKnown = LocationCoordinates(latitude = 1.0, longitude = 2.0)
        )

        anchor shouldBeEqualTo LocationCoordinates(latitude = 1.0, longitude = 2.0)
    }

    @Test
    fun resolveAnchor_givenNeither_expectNull() {
        GeofenceForegroundCoordinator.resolveAnchor(registrationCenter = null, lastKnown = null)
            .shouldBeNull()
    }
}
