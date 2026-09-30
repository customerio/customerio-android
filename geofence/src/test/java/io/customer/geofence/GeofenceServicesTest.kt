package io.customer.geofence

import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.polygon.PolygonGeofenceServiceController
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.sdk.core.util.Clock
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
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
    private val cooldownFilter: GeofenceCooldownFilter = mockk(relaxed = true)
    private val polygonController: PolygonGeofenceServiceController = mockk(relaxed = true)
    private val logger: GeofenceLogger = mockk(relaxed = true)
    private val permissionChecker: GeofencePermissionChecker = mockk(relaxed = true) {
        every { hasRequiredLocationPermissions() } returns true
        every { isBackgroundDeliveryAvailable() } returns true
    }

    private val clock: Clock = mockk { every { elapsedRealtime() } returns NOW }

    private fun servicesWith(scope: TestScope): GeofenceServicesImpl =
        GeofenceServicesImpl(
            repository = repository,
            secureUserStore = secureUserStore,
            regionStore = regionStore,
            cooldownFilter = cooldownFilter,
            polygonController = polygonController,
            scope = scope,
            logger = logger,
            permissionChecker = permissionChecker,
            clock = clock
        )

    private fun staleQuality() = GeofenceFixQuality(
        fixElapsedRealtimeMillis = NOW - GeofenceConstants.MAX_LIVE_FIX_AGE_MS - 1
    )

    @Test
    fun onLocationAcquired_givenStaleFixAfterHostRefresh_expectIntentKeptAndNoSync() = runTest(StandardTestDispatcher()) {
        every { secureUserStore.getUserId() } returns "user-42"
        val services = servicesWith(this)
        services.onRefreshRequested()

        services.onLocationAcquired(latitude = 1.0, longitude = 2.0, quality = staleQuality())
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.refreshFromLiveFix(any(), any()) }
        services.isAwaitingLocation().shouldBeTrue()
        services.isHostRefreshPending().shouldBeTrue()
    }

    @Test
    fun onLocationAcquired_givenStaleFixAfterNoLocationSkip_expectRearmFlagSurvives() = runTest(StandardTestDispatcher()) {
        every { secureUserStore.getUserId() } returns "user-42"
        val services = servicesWith(this)
        // A sync with no location arms the flag through the real skip path.
        services.onUserIdentified(latitude = null, longitude = null)
        advanceUntilIdle()
        services.isAwaitingLocation().shouldBeTrue()

        services.onLocationAcquired(latitude = 1.0, longitude = 2.0, quality = staleQuality())
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.refreshFromLiveFix(any(), any()) }
        services.isAwaitingLocation().shouldBeTrue()
    }

    @Test
    fun onLocationAcquired_givenRepeatedStaleFixes_expectNoRefreshStorm() = runTest(StandardTestDispatcher()) {
        every { secureUserStore.getUserId() } returns "user-42"
        val services = servicesWith(this)
        services.onRefreshRequested()

        repeat(5) { services.onLocationAcquired(latitude = 1.0, longitude = 2.0, quality = staleQuality()) }
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.refreshFromLiveFix(any(), any()) }
    }

    @Test
    fun onLocationAcquired_givenStaleThenFreshFix_expectFreshOneDischargesTheIntent() = runTest(StandardTestDispatcher()) {
        coEvery { repository.refreshFromLiveFix(any(), any()) } returns Result.success(Unit)
        every { secureUserStore.getUserId() } returns "user-42"
        val services = servicesWith(this)
        services.onRefreshRequested()

        services.onLocationAcquired(latitude = 1.0, longitude = 2.0, quality = staleQuality())
        advanceUntilIdle()
        val fresh = GeofenceFixQuality(fixElapsedRealtimeMillis = NOW)
        services.onLocationAcquired(latitude = 3.0, longitude = 4.0, quality = fresh)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.refreshFromLiveFix(3.0, 4.0) }
        services.isAwaitingLocation().shouldBeFalse()
    }

    @Test
    fun onMovementTriggerExit_expectHandleMovementCalled() = runTest(StandardTestDispatcher()) {
        coEvery { repository.handleMovement(any(), any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onMovementTriggerExit(latitude = 12.34, longitude = 56.78)
        advanceUntilIdle()

        coVerify { repository.handleMovement(eq(12.34), eq(56.78), any()) }
        coVerify(exactly = 0) { repository.refresh(any(), any()) }
        verify { logger.logSyncTriggered("movement-trigger-exit") }
    }

    @Test
    fun onMovementTriggerExit_givenAdaptiveRadius_expectForwardsItToRegistrationPass() =
        runTest(StandardTestDispatcher()) {
            coEvery { repository.handleMovement(any(), any(), any()) } returns Result.success(Unit)
            val services = servicesWith(this)

            services.onMovementTriggerExit(
                latitude = 12.34,
                longitude = 56.78,
                movementTriggerRadius = { 725f }
            )
            advanceUntilIdle()

            // Forwarded unevaluated, so invoke it to check the radius.
            val forwarded = slot<suspend () -> Float?>()
            coVerify { repository.handleMovement(12.34, 56.78, capture(forwarded)) }
            forwarded.captured.invoke() shouldBeEqualTo 725f
        }

    @Test
    fun onUserIdentified_expectRefreshCalled() = runTest(StandardTestDispatcher()) {
        coEvery { repository.refresh(any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onUserIdentified(latitude = 1.0, longitude = 2.0)
        advanceUntilIdle()

        coVerify { repository.refresh(1.0, 2.0) }
        coVerify(exactly = 0) { repository.handleMovement(any(), any(), any()) }
        verify { logger.logSyncTriggered("user-identified") }
    }

    @Test
    fun onUserIdentified_expectSessionOpenedBeforeTheRefresh() = runTest(StandardTestDispatcher()) {
        // beginUserSession clears routing and the last-sync stamp, so it must run first or the first
        // OS callback reads an empty routable set and removes every live fence.
        every { secureUserStore.getUserId() } returns "user-b"
        coEvery { repository.refresh(any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onUserIdentified(latitude = 1.0, longitude = 2.0)
        advanceUntilIdle()

        coVerifyOrder {
            regionStore.beginUserSession("user-b")
            repository.refresh(1.0, 2.0)
        }
    }

    @Test
    fun onUserIdentified_givenNoIdentifiedUser_expectNoSessionOpened() = runTest(StandardTestDispatcher()) {
        every { secureUserStore.getUserId() } returns null
        coEvery { repository.refresh(any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onUserIdentified(latitude = 1.0, longitude = 2.0)
        advanceUntilIdle()

        verify(exactly = 0) { regionStore.beginUserSession(any()) }
    }

    @Test
    fun onAppLaunch_expectSessionOpenedBeforeTheRefresh() = runTest(StandardTestDispatcher()) {
        // Opening it first lets the refresh arm routing; otherwise the first OS callback drops its
        // event.
        every { secureUserStore.getUserId() } returns "user-a"
        coEvery { repository.refresh(any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onAppLaunch(latitude = 1.0, longitude = 2.0)
        advanceUntilIdle()

        coVerifyOrder {
            regionStore.beginUserSessionIfAbsent("user-a")
            repository.refresh(1.0, 2.0)
        }
        verify(exactly = 0) { regionStore.beginUserSession(any()) }
    }

    @Test
    fun onAppLaunch_givenNoIdentifiedUser_expectNoSessionOpened() = runTest(StandardTestDispatcher()) {
        every { secureUserStore.getUserId() } returns null
        coEvery { repository.refresh(any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onAppLaunch(latitude = 1.0, longitude = 2.0)
        advanceUntilIdle()

        verify(exactly = 0) { regionStore.beginUserSessionIfAbsent(any()) }
    }

    @Test
    fun onAppLaunch_expectRefreshCalled() = runTest(StandardTestDispatcher()) {
        coEvery { repository.refresh(any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onAppLaunch(latitude = 1.0, longitude = 2.0)
        advanceUntilIdle()

        coVerify { repository.refresh(1.0, 2.0) }
        coVerify(exactly = 0) { repository.handleMovement(any(), any(), any()) }
        verify { logger.logSyncTriggered("app-launch") }
    }

    @Test
    fun onMovementTriggerExit_expectReturnedJobTracksRefreshCompletion() = runTest(StandardTestDispatcher()) {
        // The receiver joins this job to hold its goAsync window open.
        coEvery { repository.handleMovement(any(), any(), any()) } coAnswers {
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
        coVerify(exactly = 0) { repository.handleMovement(any(), any(), any()) }
        coVerify(exactly = 0) { repository.refresh(any(), any()) }
        verify { logger.logSyncSkippedNoLocation(any()) }
    }

    @Test
    fun onMovementTriggerExit_givenUnusableFix_expectSkipAndStayArmed() = runTest(StandardTestDispatcher()) {
        // Each makes Location.distanceBetween throw mid-sync, with no handler above it.
        val services = servicesWith(this)

        val unusable = listOf(
            Double.NaN to 2.0,
            1.0 to Double.NaN,
            Double.POSITIVE_INFINITY to 2.0,
            91.0 to 2.0,
            1.0 to 181.0
        )
        unusable.forEach { (lat, lng) ->
            services.onMovementTriggerExit(latitude = lat, longitude = lng).shouldBeNull()
        }
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.handleMovement(any(), any(), any()) }
        coVerify(exactly = 0) { repository.refresh(any(), any()) }
        verify(exactly = unusable.size) { logger.logSyncSkippedInvalidLocation(any(), any(), any()) }
        services.isAwaitingLocation() shouldBeEqualTo true
    }

    @Test
    fun onMovementTriggerExit_givenRepositoryThrows_expectLoggedAndNotRethrown() = runTest(StandardTestDispatcher()) {
        // The scope has no exception handler, so an escape would crash the host app.
        coEvery { repository.handleMovement(any(), any(), any()) } throws IllegalStateException("boom")
        val services = servicesWith(this)

        val job = services.onMovementTriggerExit(latitude = 1.0, longitude = 2.0)
        advanceUntilIdle()

        job.shouldNotBeNull()
        job.isCancelled shouldBeEqualTo false
        verify { logger.logSyncFailed(match { it?.contains("IllegalStateException") == true }) }
    }

    @Test
    fun onUserSignedOut_givenResetThrows_expectLoggedAndNotRethrown() = runTest(StandardTestDispatcher()) {
        coEvery { repository.reset() } throws IllegalStateException("boom")
        val services = servicesWith(this)

        services.onUserSignedOut()
        advanceUntilIdle()

        verify { logger.logSyncFailed(match { it?.contains("IllegalStateException") == true }) }
    }

    @Test
    fun onMovementTriggerExit_givenPermissionsNotGranted_expectSkipAndLog() = runTest(StandardTestDispatcher()) {
        every { permissionChecker.hasRequiredLocationPermissions() } returns false
        val services = servicesWith(this)

        val job = services.onMovementTriggerExit(latitude = 1.0, longitude = 2.0)
        advanceUntilIdle()

        job.shouldBeNull()
        coVerify(exactly = 0) { repository.handleMovement(any(), any(), any()) }
        coVerify(exactly = 0) { repository.refresh(any(), any()) }
        verify { logger.logSyncSkippedNoPermission(any()) }
    }

    @Test
    fun onMovementTriggerExit_givenBackgroundLocationMissing_expectProceedAndWarn() = runTest(StandardTestDispatcher()) {
        every { permissionChecker.isBackgroundDeliveryAvailable() } returns false
        coEvery { repository.handleMovement(any(), any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onMovementTriggerExit(latitude = 1.0, longitude = 2.0)
        advanceUntilIdle()

        coVerify { repository.handleMovement(eq(1.0), eq(2.0), any()) }
        verify { logger.logBackgroundDeliveryUnavailable("movement-trigger-exit") }
        verify { logger.logSyncTriggered("movement-trigger-exit") }
    }

    @Test
    fun onLocationAcquired_givenFixOneMillisecondInsideTheAgeLimit_expectSync() = runTest(StandardTestDispatcher()) {
        // The only freshness gate; the repository trusts whatever reaches refreshFromLiveFix.
        coEvery { repository.refreshFromLiveFix(any(), any()) } returns Result.success(Unit)
        every { secureUserStore.getUserId() } returns "user-1"
        val services = servicesWith(this)
        services.onRefreshRequested()

        services.onLocationAcquired(
            latitude = 12.0,
            longitude = 34.0,
            quality = GeofenceFixQuality(fixElapsedRealtimeMillis = NOW - GeofenceConstants.MAX_LIVE_FIX_AGE_MS)
        )
        advanceUntilIdle()

        coVerify { repository.refreshFromLiveFix(12.0, 34.0) }
        services.isAwaitingLocation().shouldBeFalse()
    }

    @Test
    fun onLocationAcquired_givenUnreportedTime_expectSync() = runTest(StandardTestDispatcher()) {
        // A host-supplied fix may report no time; it still asserts a position.
        coEvery { repository.refreshFromLiveFix(any(), any()) } returns Result.success(Unit)
        every { secureUserStore.getUserId() } returns "user-1"
        val services = servicesWith(this)
        services.onRefreshRequested()

        services.onLocationAcquired(latitude = 12.0, longitude = 34.0, quality = GeofenceFixQuality.UNKNOWN)
        advanceUntilIdle()

        coVerify { repository.refreshFromLiveFix(12.0, 34.0) }
    }

    @Test
    fun onLocationAcquired_givenPriorSkipAndUserIdentified_expectRefresh() = runTest(StandardTestDispatcher()) {
        every { secureUserStore.getUserId() } returns "user-1"
        coEvery { repository.refreshFromLiveFix(any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onUserIdentified(latitude = null, longitude = null)
        advanceUntilIdle()
        services.onLocationAcquired(latitude = 12.0, longitude = 34.0)
        advanceUntilIdle()

        coVerify { repository.refreshFromLiveFix(12.0, 34.0) }
    }

    @Test
    fun onLocationAcquired_givenExplicitRefreshRequested_expectRefreshWithoutPriorSkip() = runTest(StandardTestDispatcher()) {
        every { secureUserStore.getUserId() } returns "user-1"
        coEvery { repository.refreshFromLiveFix(any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onRefreshRequested()
        services.onLocationAcquired(latitude = 12.0, longitude = 34.0)
        advanceUntilIdle()

        coVerify { repository.refreshFromLiveFix(12.0, 34.0) }
    }

    @Test
    fun onLocationAcquired_givenExplicitRefreshRequested_expectConsumedOnce() = runTest(StandardTestDispatcher()) {
        every { secureUserStore.getUserId() } returns "user-1"
        coEvery { repository.refreshFromLiveFix(any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onRefreshRequested()
        services.onLocationAcquired(latitude = 12.0, longitude = 34.0)
        services.onLocationAcquired(latitude = 56.0, longitude = 78.0)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.refreshFromLiveFix(any(), any()) }
        coVerify(exactly = 0) { repository.refreshFromLiveFix(56.0, 78.0) }
    }

    @Test
    fun onLocationAcquired_givenNoPriorSkip_expectNoOp() = runTest(StandardTestDispatcher()) {
        every { secureUserStore.getUserId() } returns "user-1"
        val services = servicesWith(this)

        services.onLocationAcquired(latitude = 12.0, longitude = 34.0)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.refreshFromLiveFix(any(), any()) }
        coVerify(exactly = 0) { repository.handleMovement(any(), any(), any()) }
    }

    @Test
    fun onLocationAcquired_givenPriorSkipButUserNotIdentified_expectNoOp() = runTest(StandardTestDispatcher()) {
        every { secureUserStore.getUserId() } returns null
        val services = servicesWith(this)

        services.onUserIdentified(latitude = null, longitude = null)
        advanceUntilIdle()
        services.onLocationAcquired(latitude = 12.0, longitude = 34.0)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.refreshFromLiveFix(any(), any()) }
    }

    @Test
    fun onLocationAcquired_givenPriorSuccessfulSync_expectNoRetriggerOnNewFix() = runTest(StandardTestDispatcher()) {
        // Otherwise hosts that stream location updates would refresh on every fix.
        every { secureUserStore.getUserId() } returns "user-1"
        coEvery { repository.refresh(any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onUserIdentified(latitude = 1.0, longitude = 2.0)
        advanceUntilIdle()
        services.onLocationAcquired(latitude = 3.0, longitude = 4.0)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.refresh(any(), any()) }
        coVerify(exactly = 0) { repository.refreshFromLiveFix(any(), any()) }
    }

    @Test
    fun onLocationAcquired_afterSignOut_expectNoRefreshFromStaleRefreshFlag() = runTest(StandardTestDispatcher()) {
        every { secureUserStore.getUserId() } returns "user-1"
        coEvery { repository.refresh(any(), any()) } returns Result.success(Unit)
        coEvery { repository.reset() } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onRefreshRequested()
        services.onUserSignedOut()
        advanceUntilIdle()
        services.onLocationAcquired(latitude = 12.0, longitude = 34.0)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.refreshFromLiveFix(any(), any()) }
    }

    @Test
    fun onUserSignedOut_expectFineMonitoringAndRegistrationAnchorClearedSynchronously() =
        runTest(StandardTestDispatcher()) {
            // Cleared before reset() runs, or a re-login ranks the next user's fences around the old
            // user's location.
            coEvery { repository.reset() } returns Result.success(Unit)
            val services = servicesWith(this)

            services.onUserSignedOut()

            verify { cooldownFilter.clearAll() }
            verify { polygonController.clearUserSessionRetainingOsRegistrations() }
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
        coEvery { repository.refreshFromLiveFix(any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        // Must stay armed, or the fix the retry kicks off has nothing to consume it.
        services.onUserIdentified(latitude = null, longitude = null)
        services.onForegroundRetry(latitude = null, longitude = null)
        advanceUntilIdle()
        services.isAwaitingLocation() shouldBeEqualTo true

        services.onLocationAcquired(latitude = 12.0, longitude = 34.0)
        advanceUntilIdle()

        coVerify { repository.refreshFromLiveFix(12.0, 34.0) }
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
        coEvery { repository.refreshFromLiveFix(any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onUserIdentified(latitude = null, longitude = null)
        advanceUntilIdle()
        // Repeated foreground entries peek; only the arriving fix may consume the flag.
        services.isAwaitingLocation() shouldBeEqualTo true
        services.isAwaitingLocation() shouldBeEqualTo true

        services.onLocationAcquired(latitude = 12.0, longitude = 34.0)
        advanceUntilIdle()

        coVerify { repository.refreshFromLiveFix(12.0, 34.0) }
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

        services.isAwaitingLocation() shouldBeEqualTo false
    }

    @Test
    fun isHostRefreshPending_givenRequestThenFix_expectArmedThenConsumed() = runTest(StandardTestDispatcher()) {
        every { secureUserStore.getUserId() } returns "user-1"
        coEvery { repository.refresh(any(), any()) } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onRefreshRequested()
        services.isHostRefreshPending() shouldBeEqualTo true

        services.onLocationAcquired(latitude = 12.0, longitude = 34.0)
        advanceUntilIdle()

        services.isHostRefreshPending() shouldBeEqualTo false
    }

    @Test
    fun isHostRefreshPending_givenOnlyNoLocationSkip_expectFalse() = runTest(StandardTestDispatcher()) {
        val services = servicesWith(this)

        services.onUserIdentified(latitude = null, longitude = null)
        advanceUntilIdle()

        services.isHostRefreshPending() shouldBeEqualTo false
    }

    @Test
    fun onUserSignedOut_expectRepositoryResetInvoked() = runTest(StandardTestDispatcher()) {
        coEvery { repository.reset() } returns Result.success(Unit)
        val services = servicesWith(this)

        services.onUserSignedOut()
        advanceUntilIdle()

        coVerify { repository.reset() }
        verify { logger.logGeofenceStateResetOnSignOut() }
    }

    private companion object {
        const val NOW = 5_000_000L
    }
}
