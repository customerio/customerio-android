package io.customer.location

import io.customer.base.internal.InternalCustomerIOApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class, InternalCustomerIOApi::class)
class LocationServicesImplTest {

    // -- Mode OFF tests --

    @Test
    fun givenModeOff_setLastKnownLocation_expectNoOp() {
        val config = LocationModuleConfig.Builder()
            .setLocationTrackingMode(LocationTrackingMode.OFF)
            .build()
        val tracker: LocationTracker = mockk(relaxUnitFun = true)
        val orchestrator: LocationOrchestrator = mockk(relaxUnitFun = true)
        val logger = mockk<io.customer.sdk.core.util.Logger>(relaxUnitFun = true)
        val scope = TestScope(UnconfinedTestDispatcher())

        val services = LocationServicesImpl(config, logger, tracker, orchestrator, scope)
        services.setLastKnownLocation(37.7749, -122.4194)

        verify(exactly = 0) { tracker.onLocationReceived(any(), any()) }
    }

    @Test
    fun givenModeManual_setLastKnownLocation_expectTrackerCalled() {
        val config = LocationModuleConfig.Builder()
            .setLocationTrackingMode(LocationTrackingMode.MANUAL)
            .build()
        val tracker: LocationTracker = mockk(relaxUnitFun = true)
        val orchestrator: LocationOrchestrator = mockk(relaxUnitFun = true)
        val logger = mockk<io.customer.sdk.core.util.Logger>(relaxUnitFun = true)
        val scope = TestScope(UnconfinedTestDispatcher())

        val services = LocationServicesImpl(config, logger, tracker, orchestrator, scope)
        services.setLastKnownLocation(37.7749, -122.4194)

        verify { tracker.onLocationReceived(37.7749, -122.4194) }
    }

    @Test
    fun givenModeOnAppStart_setLastKnownLocation_expectTrackerCalled() {
        val config = LocationModuleConfig.Builder()
            .setLocationTrackingMode(LocationTrackingMode.ON_APP_START)
            .build()
        val tracker: LocationTracker = mockk(relaxUnitFun = true)
        val orchestrator: LocationOrchestrator = mockk(relaxUnitFun = true)
        val logger = mockk<io.customer.sdk.core.util.Logger>(relaxUnitFun = true)
        val scope = TestScope(UnconfinedTestDispatcher())

        val services = LocationServicesImpl(config, logger, tracker, orchestrator, scope)
        services.setLastKnownLocation(37.7749, -122.4194)

        verify { tracker.onLocationReceived(37.7749, -122.4194) }
    }

    // -- request intent routing --

    @Test
    fun requestLocationUpdateSilently_expectSilentIntent() {
        val config = LocationModuleConfig.Builder()
            .setLocationTrackingMode(LocationTrackingMode.MANUAL)
            .build()
        val tracker: LocationTracker = mockk(relaxUnitFun = true)
        val orchestrator: LocationOrchestrator = mockk(relaxUnitFun = true)
        val logger = mockk<io.customer.sdk.core.util.Logger>(relaxUnitFun = true)
        val scope = TestScope(UnconfinedTestDispatcher())
        val intentSlot = slot<LocationRequestIntent>()
        coEvery { orchestrator.requestLocation(capture(intentSlot)) } just runs

        val services = LocationServicesImpl(config, logger, tracker, orchestrator, scope)
        services.requestLocationUpdateSilently()

        coVerify(exactly = 1) { orchestrator.requestLocation(any()) }
        intentSlot.captured.isTracked.shouldBeFalse()
    }

    @Test
    fun requestLocationUpdate_givenSilentRequestInFlight_expectUpgradeNotSecondFetch() {
        val config = LocationModuleConfig.Builder()
            .setLocationTrackingMode(LocationTrackingMode.ON_APP_START)
            .build()
        val tracker: LocationTracker = mockk(relaxUnitFun = true)
        val orchestrator: LocationOrchestrator = mockk(relaxUnitFun = true)
        val logger = mockk<io.customer.sdk.core.util.Logger>(relaxUnitFun = true)
        val scope = TestScope(UnconfinedTestDispatcher())
        val gate = CompletableDeferred<Unit>()
        val intentSlot = slot<LocationRequestIntent>()
        coEvery { orchestrator.requestLocation(capture(intentSlot)) } coAnswers { gate.await() }

        val services = LocationServicesImpl(config, logger, tracker, orchestrator, scope)
        services.requestLocationUpdateSilently()
        intentSlot.captured.isTracked.shouldBeFalse()

        services.requestLocationUpdate()

        // The tracked request upgrades the in-flight silent one instead of being dropped
        // or starting a second fetch.
        intentSlot.captured.isTracked.shouldBeTrue()
        coVerify(exactly = 1) { orchestrator.requestLocation(any()) }
        gate.complete(Unit)
    }

    @Test
    fun requestLocationUpdate_givenInFlightRequestAlreadyDelivered_expectFreshFetch() {
        // The in-flight request has handed its fix off but its job is still alive, so upgrading
        // would be swallowed — the tracked caller needs its own fetch.
        val config = LocationModuleConfig.Builder()
            .setLocationTrackingMode(LocationTrackingMode.ON_APP_START)
            .build()
        val tracker: LocationTracker = mockk(relaxUnitFun = true)
        val orchestrator: LocationOrchestrator = mockk(relaxUnitFun = true)
        val logger = mockk<io.customer.sdk.core.util.Logger>(relaxUnitFun = true)
        val scope = TestScope(UnconfinedTestDispatcher())
        val gate = CompletableDeferred<Unit>()
        val intents = mutableListOf<LocationRequestIntent>()
        val routedTracked = mutableListOf<Boolean>()
        coEvery { orchestrator.requestLocation(capture(intents)) } coAnswers {
            // Deliver, then keep the job alive so the second call still sees it as in flight.
            routedTracked += intents.last().claimTrackedDelivery()
            gate.await()
        }

        val services = LocationServicesImpl(config, logger, tracker, orchestrator, scope)
        services.requestLocationUpdateSilently()

        services.requestLocationUpdate()

        // The second fetch is the one that routes tracked.
        coVerify(exactly = 2) { orchestrator.requestLocation(any()) }
        routedTracked shouldBeEqualTo listOf(false, true)
        gate.complete(Unit)
    }

    @Test
    fun requestLocationUpdateSilently_givenTrackedRequestInFlight_expectDropped() {
        val config = LocationModuleConfig.Builder()
            .setLocationTrackingMode(LocationTrackingMode.ON_APP_START)
            .build()
        val tracker: LocationTracker = mockk(relaxUnitFun = true)
        val orchestrator: LocationOrchestrator = mockk(relaxUnitFun = true)
        val logger = mockk<io.customer.sdk.core.util.Logger>(relaxUnitFun = true)
        val scope = TestScope(UnconfinedTestDispatcher())
        val gate = CompletableDeferred<Unit>()
        val intentSlot = slot<LocationRequestIntent>()
        coEvery { orchestrator.requestLocation(capture(intentSlot)) } coAnswers { gate.await() }

        val services = LocationServicesImpl(config, logger, tracker, orchestrator, scope)
        services.requestLocationUpdate()

        services.requestLocationUpdateSilently()

        // Tracked delivery is a superset of silent (both update lastKnownLocation and
        // publish LocationAcquired), so the silent request needs nothing extra.
        intentSlot.captured.isTracked.shouldBeTrue()
        coVerify(exactly = 1) { orchestrator.requestLocation(any()) }
        gate.complete(Unit)
    }

    @Test
    fun requestLocationUpdate_givenPreviousRequestCompleted_expectFreshFetch() {
        val config = LocationModuleConfig.Builder()
            .setLocationTrackingMode(LocationTrackingMode.ON_APP_START)
            .build()
        val tracker: LocationTracker = mockk(relaxUnitFun = true)
        val orchestrator: LocationOrchestrator = mockk(relaxUnitFun = true)
        val logger = mockk<io.customer.sdk.core.util.Logger>(relaxUnitFun = true)
        val scope = TestScope(UnconfinedTestDispatcher())
        coEvery { orchestrator.requestLocation(any()) } just runs

        val services = LocationServicesImpl(config, logger, tracker, orchestrator, scope)
        services.requestLocationUpdateSilently()

        services.requestLocationUpdate()

        coVerify(exactly = 2) { orchestrator.requestLocation(any()) }
    }
}
