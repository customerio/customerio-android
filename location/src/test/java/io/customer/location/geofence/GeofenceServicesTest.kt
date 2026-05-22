package io.customer.location.geofence

import io.customer.commontest.core.RobolectricTest
import io.customer.sdk.data.store.SecureUserStore
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
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val logger: GeofenceLogger = mockk(relaxed = true)
    private val permissionChecker: GeofencePermissionChecker = mockk(relaxed = true) {
        every { hasRequiredLocationPermissions() } returns true
        every { isBackgroundDeliveryAvailable() } returns true
    }

    private fun servicesWith(scope: TestScope): GeofenceServicesImpl =
        GeofenceServicesImpl(
            repository = repository,
            secureUserStore = secureUserStore,
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
    fun onMovementTriggerExit_givenBackgroundLocationMissing_expectProceedAndWarn() = runTest(StandardTestDispatcher()) {
        every { permissionChecker.isBackgroundDeliveryAvailable() } returns false
        coEvery { repository.handleMovement(any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onMovementTriggerExit(latitude = 1.0, longitude = 2.0)
        advanceUntilIdle()

        coVerify { repository.handleMovement(1.0, 2.0) }
        verify { logger.logBackgroundDeliveryUnavailable("movement-trigger-exit") }
        verify { logger.logSyncTriggered("movement-trigger-exit") }
    }

    @Test
    fun onLocationAcquired_givenPriorSkipAndUserIdentified_expectRefresh() = runTest(StandardTestDispatcher()) {
        every { secureUserStore.getUserId() } returns "user-1"
        coEvery { repository.refresh(any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        // Skip first, then deliver the fix.
        services.onUserIdentified(latitude = null, longitude = null)
        advanceUntilIdle()
        services.onLocationAcquired(latitude = 12.0, longitude = 34.0)
        advanceUntilIdle()

        coVerify { repository.refresh(12.0, 34.0) }
    }

    @Test
    fun onLocationAcquired_givenNoPriorSkip_expectNoOp() = runTest(StandardTestDispatcher()) {
        every { secureUserStore.getUserId() } returns "user-1"
        val services = servicesWith(this)

        services.onLocationAcquired(latitude = 12.0, longitude = 34.0)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.refresh(any(), any()) }
        coVerify(exactly = 0) { repository.handleMovement(any(), any()) }
    }

    @Test
    fun onLocationAcquired_givenPriorSkipButUserNotIdentified_expectNoOp() = runTest(StandardTestDispatcher()) {
        every { secureUserStore.getUserId() } returns null
        val services = servicesWith(this)

        services.onUserIdentified(latitude = null, longitude = null)
        advanceUntilIdle()
        services.onLocationAcquired(latitude = 12.0, longitude = 34.0)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.refresh(any(), any()) }
    }

    @Test
    fun onLocationAcquired_givenPriorSuccessfulSync_expectNoRetriggerOnNewFix() = runTest(StandardTestDispatcher()) {
        // Successful trigger must clear the rearm flag — otherwise hosts that
        // stream location updates would refresh on every fix.
        every { secureUserStore.getUserId() } returns "user-1"
        coEvery { repository.refresh(any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onUserIdentified(latitude = 1.0, longitude = 2.0)
        advanceUntilIdle()
        services.onLocationAcquired(latitude = 3.0, longitude = 4.0)
        advanceUntilIdle()

        // Only the initial identify call should reach the repository.
        coVerify(exactly = 1) { repository.refresh(any(), any()) }
        coVerify(exactly = 0) { repository.refresh(3.0, 4.0) }
    }

    @Test
    fun onUserSignedOut_expectSignedOutUserIdCapturedSynchronouslyAndPassedToReset() = runTest(StandardTestDispatcher()) {
        // The capture has to happen BEFORE scope.launch — otherwise a racing
        // ResetEvent subscriber (datapipelines clears secureUserStore) could
        // null it out by the time reset() reads it.
        every { secureUserStore.getUserId() } returns "user-42"
        coEvery { repository.reset(any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onUserSignedOut()
        advanceUntilIdle()

        coVerify { repository.reset("user-42") }
        verify { logger.logGeofenceStateResetOnSignOut() }
    }
}
