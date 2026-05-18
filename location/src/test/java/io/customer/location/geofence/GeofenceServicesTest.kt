package io.customer.location.geofence

import io.customer.commontest.core.RobolectricTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GeofenceServicesTest : RobolectricTest() {

    private val repository: GeofenceRepository = mockk(relaxed = true)
    private val logger: GeofenceLogger = mockk(relaxed = true)
    private val permissionChecker: GeofencePermissionChecker = mockk(relaxed = true) {
        every { hasRequiredLocationPermissions() } returns true
    }

    private fun servicesWith(scope: TestScope): GeofenceServicesImpl =
        GeofenceServicesImpl(
            repository = repository,
            scope = scope,
            logger = logger,
            permissionChecker = permissionChecker
        )

    @Test
    fun triggerSync_givenLocationAndPermissions_expectRefreshLaunched() = runTest(StandardTestDispatcher()) {
        coEvery { repository.refresh(any(), any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onMovementTriggerExit(latitude = 12.34, longitude = 56.78)
        advanceUntilIdle()

        coVerify { repository.refresh(12.34, 56.78, any()) }
        verify { logger.logSyncTriggered(any()) }
    }

    @Test
    fun onMovementTriggerExit_expectForceTrue() = runTest(StandardTestDispatcher()) {
        // Movement EXIT must force-refresh so the trigger's center can be updated.
        coEvery { repository.refresh(any(), any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onMovementTriggerExit(latitude = 1.0, longitude = 2.0)
        advanceUntilIdle()

        coVerify { repository.refresh(1.0, 2.0, force = true) }
    }

    @Test
    fun onUserIdentified_expectForceFalse() = runTest(StandardTestDispatcher()) {
        // Identify honours the freshness threshold so repeated identify is a no-op.
        coEvery { repository.refresh(any(), any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onUserIdentified(latitude = 1.0, longitude = 2.0)
        advanceUntilIdle()

        coVerify { repository.refresh(1.0, 2.0, force = false) }
    }

    @Test
    fun onAppLaunch_givenLocation_expectRefreshLaunchedWithForceFalse() = runTest(StandardTestDispatcher()) {
        // App-launch trigger honours threshold; redundant with identify in the common case.
        coEvery { repository.refresh(any(), any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onAppLaunch(latitude = 1.0, longitude = 2.0)
        advanceUntilIdle()

        coVerify { repository.refresh(1.0, 2.0, force = false) }
        verify { logger.logSyncTriggered("app-launch") }
    }

    @Test
    fun triggerSync_givenNullLocation_expectSkipAndLog() = runTest(StandardTestDispatcher()) {
        val services = servicesWith(this)

        services.onMovementTriggerExit(latitude = null, longitude = 12.0)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.refresh(any(), any(), any()) }
        verify { logger.logSyncSkippedNoLocation(any()) }
    }

    @Test
    fun triggerSync_givenPermissionsNotGranted_expectSkipAndLog() = runTest(StandardTestDispatcher()) {
        every { permissionChecker.hasRequiredLocationPermissions() } returns false
        val services = servicesWith(this)

        services.onMovementTriggerExit(latitude = 1.0, longitude = 2.0)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.refresh(any(), any(), any()) }
        verify { logger.logSyncSkippedNoPermission(any()) }
    }

    @Test
    fun onUserSignedOut_expectRepositoryResetLaunched() = runTest(StandardTestDispatcher()) {
        // Sign-out hands off to repository.reset, which clears persisted state and
        // OS-side registrations via the manager.
        coEvery { repository.reset() } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onUserSignedOut()
        advanceUntilIdle()

        coVerify { repository.reset() }
        verify { logger.logGeofenceStateResetOnSignOut() }
    }

    @Test
    fun triggerSync_expectReasonStringMatchesTriggerSource() = runTest(StandardTestDispatcher()) {
        // Pins the only behavioral difference between the trigger methods: the reason string.
        coEvery { repository.refresh(any(), any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onMovementTriggerExit(latitude = 1.0, longitude = 2.0)
        services.onUserIdentified(latitude = 3.0, longitude = 4.0)
        services.onAppLaunch(latitude = 5.0, longitude = 6.0)
        advanceUntilIdle()

        verify { logger.logSyncTriggered("movement-trigger-exit") }
        verify { logger.logSyncTriggered("user-identified") }
        verify { logger.logSyncTriggered("app-launch") }
    }
}
