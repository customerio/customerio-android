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

    /**
     * The real coordinator. These tests used to construct a `ModuleGeofence` and reach into
     * `@VisibleForTesting` methods on it; the decisions now live in a class that can simply be
     * built, which is the point of the extraction.
     */
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

    // MARK: - onForeground: the sequencing the harness used to reimplement, and got wrong

    @Test
    fun onForeground_givenAutomaticAndIdentified_expectFixTakenAndNoRetry() = runTest {
        coordinatorWith(GeofenceLocationMode.AUTOMATIC).onForeground()

        verifyOrder {
            mockServices.onRefreshRequested()
            mockLocationServices.requestLocationUpdateSilently()
        }
        // takeFixForRefresh succeeded, so the stuck-sync path must not also run.
        verify(exactly = 0) { mockServices.onForegroundRetry(any(), any()) }
    }

    @Test
    fun onForeground_givenManual_expectRetryPathInsteadOfTakingAFix() = runTest {
        // The gate the replay harness dropped. MANUAL leaves fixes to the host, so foreground must
        // fall through to the retry rather than arming and requesting one.
        every { mockServices.isAwaitingLocation() } returns true

        coordinatorWith(GeofenceLocationMode.MANUAL).onForeground()

        verify(exactly = 0) { mockServices.onRefreshRequested() }
        verify { mockServices.onForegroundRetry(any(), any()) }
    }

    @Test
    fun onForeground_givenSyncAwaitingLocation_expectRetryFromAnchor() = runTest {
        // The fallback the harness dropped entirely: a sync stuck without a fix gets one more
        // chance from the stored anchor, and nothing else re-requests until the next cold launch.
        every { mockServices.isAwaitingLocation() } returns true
        every { mockRegionStore.getLastMovementTriggerLocation() } returns GeofenceLocation(1.5, 2.5)

        coordinatorWith(GeofenceLocationMode.AUTOMATIC).onForeground()

        verify { mockServices.onForegroundRetry(latitude = 1.5, longitude = 2.5) }
    }

    @Test
    fun onForeground_givenHostRefreshPending_expectLiveFixRequestedNotAnchorSync() = runTest {
        // A host asked for a *live* fix; satisfying it from a stored anchor would consume the flag
        // with a position the host did not ask for.
        every { mockServices.isAwaitingLocation() } returns true
        every { mockServices.isHostRefreshPending() } returns true

        coordinatorWith(GeofenceLocationMode.AUTOMATIC).onForeground()

        verify { mockLocationServices.requestLocationUpdateSilently() }
        verify(exactly = 0) { mockServices.onForegroundRetry(any(), any()) }
    }

    @Test
    fun onForeground_givenFixLandsDuringTheAnchorRead_expectNoRetry() = runTest {
        // Re-checked after the anchor read: a fix that arrived meanwhile has already consumed the
        // flags and synced, so retrying now would re-center on the pre-fix anchor.
        // Three reads, not two: takeFixForRefresh checks first and bails, then the retry guard
        // checks, then the post-anchor re-check. With only two values the second read returned
        // false and the retry exited at its guard — the test passed without ever reaching the
        // re-check it names.
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
    fun refreshOnForeground_givenAutomatic_expectArmedAndSilentFetch() {
        coordinatorWith(GeofenceLocationMode.AUTOMATIC, secureUserStore = identifiedUserStore)
            .takeFixForRefresh() shouldBeEqualTo true

        // Arming after the request would race the fix and drop it.
        verifyOrder {
            mockServices.onRefreshRequested()
            mockLocationServices.requestLocationUpdateSilently()
        }
    }

    @Test
    fun refreshOnForeground_givenManual_expectNoFetchOrArm() {
        coordinatorWith(GeofenceLocationMode.MANUAL, secureUserStore = identifiedUserStore)
            .takeFixForRefresh() shouldBeEqualTo false

        verify(exactly = 0) { mockServices.onRefreshRequested() }
        verify(exactly = 0) { mockLocationServices.requestLocationUpdateSilently() }
    }

    @Test
    fun refreshOnForeground_givenSyncAlreadyAwaitingLocation_expectDeferredToStuckSyncPath() {
        // Stub the collaborator the coordinator actually holds. A locally-declared mock here would
        // be shadowed by the field and the test would pass for the wrong reason.
        every { mockServices.isAwaitingLocation() } returns true

        coordinatorWith(GeofenceLocationMode.AUTOMATIC, secureUserStore = identifiedUserStore)
            .takeFixForRefresh() shouldBeEqualTo false

        verify(exactly = 0) { mockServices.onRefreshRequested() }
        verify(exactly = 0) { mockLocationServices.requestLocationUpdateSilently() }
    }

    @Test
    fun refreshOnForeground_givenNoIdentifiedUser_expectNoFetchOrArm() {
        // Resume before the first identify, and every resume after sign-out. The sync discards a fix
        // that arrives with nobody identified, so requesting one is spent battery.
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
