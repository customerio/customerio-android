package io.customer.location

import io.customer.location.provider.LocationProvider
import io.customer.location.type.AuthorizationStatus
import io.customer.location.type.LocationGranularity
import io.customer.location.type.LocationSnapshot
import io.customer.sdk.core.util.Logger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Date
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocationOrchestratorTest {

    private val logger = mockk<Logger>(relaxUnitFun = true)
    private val tracker = mockk<LocationTracker>(relaxUnitFun = true)
    private val provider = mockk<LocationProvider>()

    private fun orchestrator(mode: LocationTrackingMode) = LocationOrchestrator(
        config = LocationModuleConfig.Builder().setLocationTrackingMode(mode).build(),
        logger = logger,
        locationTracker = tracker,
        locationProvider = provider
    )

    private fun givenAuthorizedFix() {
        coEvery { provider.currentAuthorizationStatus() } returns AuthorizationStatus.AUTHORIZED_FOREGROUND
        coEvery { provider.requestLocation(LocationGranularity.DEFAULT) } returns LocationSnapshot(
            latitude = 37.7749,
            longitude = -122.4194,
            timestamp = Date(),
            horizontalAccuracy = 10.0
        )
    }

    @Test
    fun requestLocation_givenTrackedAndModeOff_expectNoFix() = runTest {
        orchestrator(LocationTrackingMode.OFF).requestLocation(LocationRequestIntent(tracked = true))

        coVerify(exactly = 0) { provider.requestLocation(any()) }
        verify(exactly = 0) { tracker.onLocationReceived(any(), any()) }
    }

    @Test
    fun requestLocation_givenTrackedAndModeEnabledAndAuthorized_expectTrackedFix() = runTest {
        givenAuthorizedFix()

        orchestrator(LocationTrackingMode.MANUAL).requestLocation(LocationRequestIntent(tracked = true))

        verify { tracker.onLocationReceived(37.7749, -122.4194) }
        verify(exactly = 0) { tracker.onLocationReceivedWithoutTracking(any(), any()) }
    }

    @Test
    fun requestLocation_givenSilentAndModeOff_expectFixWithoutTracking() = runTest {
        givenAuthorizedFix()

        orchestrator(LocationTrackingMode.OFF).requestLocation(LocationRequestIntent(tracked = false))

        coVerify { provider.requestLocation(LocationGranularity.DEFAULT) }
        verify { tracker.onLocationReceivedWithoutTracking(37.7749, -122.4194) }
        verify(exactly = 0) { tracker.onLocationReceived(any(), any()) }
    }

    @Test
    fun requestLocation_givenSilentAndPermissionDenied_expectNoFix() = runTest {
        coEvery { provider.currentAuthorizationStatus() } returns AuthorizationStatus.DENIED

        orchestrator(LocationTrackingMode.OFF).requestLocation(LocationRequestIntent(tracked = false))

        coVerify(exactly = 0) { provider.requestLocation(any()) }
        verify(exactly = 0) { tracker.onLocationReceivedWithoutTracking(any(), any()) }
    }

    @Test
    fun requestLocation_givenUpgradeRacesTheHandoff_expectRejected() = runTest {
        // Upgrading at the moment the fix is handed off must fail, or the tracked caller is dropped
        // while its fix goes out silently. Observed from inside the delivery callback.
        givenAuthorizedFix()
        val intent = LocationRequestIntent(tracked = false)
        var upgradeAccepted: Boolean? = null
        every { tracker.onLocationReceivedWithoutTracking(any(), any()) } answers {
            upgradeAccepted = intent.upgradeToTracked()
        }

        orchestrator(LocationTrackingMode.MANUAL).requestLocation(intent)

        upgradeAccepted shouldBeEqualTo false
    }

    @Test
    fun requestLocation_givenRequestEndedWithoutDelivering_expectIntentClosed() = runTest {
        // A closed intent is what tells a later tracked caller to fetch its own fix instead of
        // waiting on a request that can no longer deliver.
        coEvery { provider.currentAuthorizationStatus() } returns AuthorizationStatus.DENIED
        val intent = LocationRequestIntent(tracked = false)

        orchestrator(LocationTrackingMode.MANUAL).requestLocation(intent)

        intent.upgradeToTracked().shouldBeFalse()
    }

    @Test
    fun requestLocation_givenSilentUpgradedMidFetch_expectTrackedDelivery() = runTest {
        // Tracked request arrives while the silent fetch is in flight: the same
        // fix must reach the tracked path (trackedLocation, persistence, track event).
        coEvery { provider.currentAuthorizationStatus() } returns AuthorizationStatus.AUTHORIZED_FOREGROUND
        val intent = LocationRequestIntent(tracked = false)
        coEvery { provider.requestLocation(LocationGranularity.DEFAULT) } answers {
            intent.upgradeToTracked()
            LocationSnapshot(latitude = 37.7749, longitude = -122.4194, timestamp = Date(), horizontalAccuracy = 10.0)
        }

        orchestrator(LocationTrackingMode.ON_APP_START).requestLocation(intent)

        verify { tracker.onLocationReceived(37.7749, -122.4194) }
        verify(exactly = 0) { tracker.onLocationReceivedWithoutTracking(any(), any()) }
    }

    @Test
    fun requestLocation_givenSilentUpgradedButModeOff_expectSilentDelivery() = runTest {
        // An upgrade must never make a disabled tracking mode emit analytics.
        coEvery { provider.currentAuthorizationStatus() } returns AuthorizationStatus.AUTHORIZED_FOREGROUND
        val intent = LocationRequestIntent(tracked = false)
        coEvery { provider.requestLocation(LocationGranularity.DEFAULT) } answers {
            intent.upgradeToTracked()
            LocationSnapshot(latitude = 37.7749, longitude = -122.4194, timestamp = Date(), horizontalAccuracy = 10.0)
        }

        orchestrator(LocationTrackingMode.OFF).requestLocation(intent)

        verify { tracker.onLocationReceivedWithoutTracking(37.7749, -122.4194) }
        verify(exactly = 0) { tracker.onLocationReceived(any(), any()) }
    }
}
