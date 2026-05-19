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
    fun onMovementTriggerExit_expectHandleMovementCalled() = runTest(StandardTestDispatcher()) {
        coEvery { repository.handleMovement(any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onMovementTriggerExit(latitude = 12.34, longitude = 56.78)
        advanceUntilIdle()

        coVerify { repository.handleMovement(12.34, 56.78) }
        coVerify(exactly = 0) { repository.refresh(any(), any()) }
        verify { logger.logSyncTriggered("movement-trigger-exit") }
    }

    @Test
    fun onUserIdentified_expectRefreshCalled() = runTest(StandardTestDispatcher()) {
        coEvery { repository.refresh(any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onUserIdentified(latitude = 1.0, longitude = 2.0)
        advanceUntilIdle()

        coVerify { repository.refresh(1.0, 2.0) }
        coVerify(exactly = 0) { repository.handleMovement(any(), any()) }
        verify { logger.logSyncTriggered("user-identified") }
    }

    @Test
    fun onAppLaunch_expectRefreshCalled() = runTest(StandardTestDispatcher()) {
        coEvery { repository.refresh(any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onAppLaunch(latitude = 1.0, longitude = 2.0)
        advanceUntilIdle()

        coVerify { repository.refresh(1.0, 2.0) }
        coVerify(exactly = 0) { repository.handleMovement(any(), any()) }
        verify { logger.logSyncTriggered("app-launch") }
    }

    @Test
    fun onMovementTriggerExit_givenNullLocation_expectSkipAndLog() = runTest(StandardTestDispatcher()) {
        val services = servicesWith(this)

        services.onMovementTriggerExit(latitude = null, longitude = 12.0)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.handleMovement(any(), any()) }
        coVerify(exactly = 0) { repository.refresh(any(), any()) }
        verify { logger.logSyncSkippedNoLocation(any()) }
    }

    @Test
    fun onMovementTriggerExit_givenPermissionsNotGranted_expectSkipAndLog() = runTest(StandardTestDispatcher()) {
        every { permissionChecker.hasRequiredLocationPermissions() } returns false
        val services = servicesWith(this)

        services.onMovementTriggerExit(latitude = 1.0, longitude = 2.0)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.handleMovement(any(), any()) }
        coVerify(exactly = 0) { repository.refresh(any(), any()) }
        verify { logger.logSyncSkippedNoPermission(any()) }
    }

    @Test
    fun onUserSignedOut_expectRepositoryResetLaunched() = runTest(StandardTestDispatcher()) {
        coEvery { repository.reset() } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onUserSignedOut()
        advanceUntilIdle()

        coVerify { repository.reset() }
        verify { logger.logGeofenceStateResetOnSignOut() }
    }
}
