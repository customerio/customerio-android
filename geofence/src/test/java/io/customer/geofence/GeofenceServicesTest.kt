package io.customer.geofence

import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GeofenceServicesTest : RobolectricTest() {

    private val repository: GeofenceRepository = mockk(relaxed = true)
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val regionStore: GeofenceRegionStore = mockk(relaxed = true)
    private val logger: GeofenceLogger = mockk(relaxed = true)
    private val permissionChecker: GeofencePermissionChecker = mockk(relaxed = true) {
        every { hasRequiredLocationPermissions() } returns true
        every { isBackgroundDeliveryAvailable() } returns true
    }

    private fun servicesWith(scope: TestScope): GeofenceServicesImpl =
        GeofenceServicesImpl(
            repository = repository,
            secureUserStore = secureUserStore,
            regionStore = regionStore,
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
    fun onMovementTriggerExit_expectReturnedJobTracksRefreshCompletion() = runTest(StandardTestDispatcher()) {
        // The receiver holds its goAsync window open by joining this job; a job that
        // completes before the refresh finishes would let the OS kill the process
        // mid-fetch/mid-re-registration.
        coEvery { repository.handleMovement(any(), any()) } coAnswers {
            delay(1_000)
            Result.success(Unit)
        }
        val services = servicesWith(this)

        val job = services.onMovementTriggerExit(latitude = 1.0, longitude = 2.0).shouldNotBeNull()

        job.isCompleted shouldBeEqualTo false
        advanceUntilIdle()
        job.isCompleted shouldBeEqualTo true
    }

    @Test
    fun onMovementTriggerExit_givenNullLocation_expectSkipAndLog() = runTest(StandardTestDispatcher()) {
        val services = servicesWith(this)

        val job = services.onMovementTriggerExit(latitude = null, longitude = 12.0)
        advanceUntilIdle()

        job.shouldBeNull()
        coVerify(exactly = 0) { repository.handleMovement(any(), any()) }
        coVerify(exactly = 0) { repository.refresh(any(), any()) }
        verify { logger.logSyncSkippedNoLocation(any()) }
    }

    @Test
    fun onMovementTriggerExit_givenPermissionsNotGranted_expectSkipAndLog() = runTest(StandardTestDispatcher()) {
        every { permissionChecker.hasRequiredLocationPermissions() } returns false
        val services = servicesWith(this)

        val job = services.onMovementTriggerExit(latitude = 1.0, longitude = 2.0)
        advanceUntilIdle()

        job.shouldBeNull()
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
    fun onLocationAcquired_givenExplicitRefreshRequested_expectRefreshWithoutPriorSkip() = runTest(StandardTestDispatcher()) {
        every { secureUserStore.getUserId() } returns "user-1"
        coEvery { repository.refresh(any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        // Host-initiated refresh arms the pipeline; the returning fix drives the sync
        // even without any prior no-location skip.
        services.onRefreshRequested()
        services.onLocationAcquired(latitude = 12.0, longitude = 34.0)
        advanceUntilIdle()

        coVerify { repository.refresh(12.0, 34.0) }
    }

    @Test
    fun onLocationAcquired_givenExplicitRefreshRequested_expectConsumedOnce() = runTest(StandardTestDispatcher()) {
        every { secureUserStore.getUserId() } returns "user-1"
        coEvery { repository.refresh(any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onRefreshRequested()
        services.onLocationAcquired(latitude = 12.0, longitude = 34.0)
        services.onLocationAcquired(latitude = 56.0, longitude = 78.0)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.refresh(any(), any()) }
        coVerify(exactly = 0) { repository.refresh(56.0, 78.0) }
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
    fun onLocationAcquired_afterSignOut_expectNoRefreshFromStaleRefreshFlag() = runTest(StandardTestDispatcher()) {
        // A pending refresh from the previous session must not survive sign-out and
        // drive a sync for the next user's first fix.
        every { secureUserStore.getUserId() } returns "user-1"
        coEvery { repository.refresh(any(), any()) } returns Result.success(Unit)
        coEvery { repository.reset() } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onRefreshRequested()
        services.onUserSignedOut()
        advanceUntilIdle()
        services.onLocationAcquired(latitude = 12.0, longitude = 34.0)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.refresh(any(), any()) }
    }

    @Test
    fun onUserSignedOut_expectRegistrationAnchorClearedSynchronously() = runTest(StandardTestDispatcher()) {
        // The persisted registration center is user-scoped. It must be dropped
        // synchronously on sign-out — before repository.reset() runs on the scope —
        // so an in-process re-login can't rank the next user's geofences around the
        // previous user's location.
        coEvery { repository.reset() } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onUserSignedOut()

        verify { regionStore.clearLastMovementTriggerLocation() }
    }

    @Test
    fun onForegroundRetry_expectRefreshUnderItsOwnReason() = runTest(StandardTestDispatcher()) {
        coEvery { repository.refresh(any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onForegroundRetry(latitude = 1.0, longitude = 2.0)
        advanceUntilIdle()

        coVerify { repository.refresh(1.0, 2.0) }
        verify { logger.logSyncTriggered("foreground-retry") }
    }

    @Test
    fun onForegroundRetry_givenStillNoLocation_expectStaysArmed() = runTest(StandardTestDispatcher()) {
        every { secureUserStore.getUserId() } returns "user-1"
        coEvery { repository.refresh(any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        // A retry that still has no anchor must leave the flag up, or the fix it kicks off
        // arrives with nothing armed to consume it.
        services.onUserIdentified(latitude = null, longitude = null)
        services.onForegroundRetry(latitude = null, longitude = null)
        advanceUntilIdle()
        services.isAwaitingLocation() shouldBeEqualTo true

        services.onLocationAcquired(latitude = 12.0, longitude = 34.0)
        advanceUntilIdle()

        coVerify { repository.refresh(12.0, 34.0) }
    }

    @Test
    fun isAwaitingLocation_givenNoLocationSkip_expectTrue() = runTest(StandardTestDispatcher()) {
        val services = servicesWith(this)

        services.onUserIdentified(latitude = null, longitude = null)
        advanceUntilIdle()

        services.isAwaitingLocation() shouldBeEqualTo true
    }

    @Test
    fun isAwaitingLocation_givenExplicitRefreshRequested_expectTrue() = runTest(StandardTestDispatcher()) {
        val services = servicesWith(this)

        services.onRefreshRequested()

        services.isAwaitingLocation() shouldBeEqualTo true
    }

    @Test
    fun isAwaitingLocation_givenSuccessfulTrigger_expectFalse() = runTest(StandardTestDispatcher()) {
        coEvery { repository.refresh(any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onUserIdentified(latitude = 1.0, longitude = 2.0)
        advanceUntilIdle()

        services.isAwaitingLocation() shouldBeEqualTo false
    }

    @Test
    fun isAwaitingLocation_expectPeekLeavesFlagForTheReturningFix() = runTest(StandardTestDispatcher()) {
        every { secureUserStore.getUserId() } returns "user-1"
        coEvery { repository.refresh(any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onUserIdentified(latitude = null, longitude = null)
        advanceUntilIdle()
        // Repeated foreground entries peek; only the arriving fix may consume the flag.
        services.isAwaitingLocation() shouldBeEqualTo true
        services.isAwaitingLocation() shouldBeEqualTo true

        services.onLocationAcquired(latitude = 12.0, longitude = 34.0)
        advanceUntilIdle()

        coVerify { repository.refresh(12.0, 34.0) }
        services.isAwaitingLocation() shouldBeEqualTo false
    }

    @Test
    fun isAwaitingLocation_afterSignOut_expectFalse() = runTest(StandardTestDispatcher()) {
        coEvery { repository.reset() } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onUserIdentified(latitude = null, longitude = null)
        advanceUntilIdle()
        services.onUserSignedOut()
        advanceUntilIdle()

        // Otherwise the next user's first foreground entry would retry the previous user's skip.
        services.isAwaitingLocation() shouldBeEqualTo false
    }

    @Test
    fun onUserSignedOut_expectRepositoryResetInvoked() = runTest(StandardTestDispatcher()) {
        // Sign-out delegates the wipe decision to repository.reset(); the services layer
        // just drops the anchor synchronously and kicks reset off the scope.
        coEvery { repository.reset() } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onUserSignedOut()
        advanceUntilIdle()

        coVerify { repository.reset() }
        verify { logger.logGeofenceStateResetOnSignOut() }
    }
}
