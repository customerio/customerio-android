package io.customer.geofence.polygon

import android.Manifest
import android.app.Application
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.util.concurrent.Futures
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.sdk.core.util.CustomerIOWorkManagerProvider
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The periodic re-check, for a device already inside a wake circle when it is registered: GMS may
 * never report that ENTER, and `emitInitialEnters` skips polygons.
 */
@RunWith(RobolectricTestRunner::class)
class PolygonRecheckTest : RobolectricTest() {
    private val workManagerProvider: CustomerIOWorkManagerProvider = mockk(relaxed = true)
    private val workManager: WorkManager = mockk(relaxed = true)
    private val mockStore: GeofenceRegionStore = mockk(relaxed = true)
    private val mockController: PolygonGeofenceServiceController = mockk(relaxed = true)
    private val mockSecureUserStore: SecureUserStore = mockk(relaxed = true)
    private val mockFreshFix: PolygonFreshFixSource = mockk(relaxed = true)
    private val mockRecheckScheduler: PolygonRecheckScheduler = mockk(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
                diGraph {
                    android {
                        overrideDependency<GeofenceRegionStore>(mockStore)
                        overrideDependency<PolygonGeofenceServiceController>(mockController)
                        overrideDependency<SecureUserStore>(mockSecureUserStore)
                        overrideDependency<PolygonFreshFixSource>(mockFreshFix)
                        overrideDependency<PolygonRecheckScheduler>(mockRecheckScheduler)
                    }
                }
            }
        )
        every { mockSecureUserStore.getUserId() } returns "user-1"
        every { mockStore.userStateGeneration() } returns 7L
        grantLocationPermission()
        every { workManagerProvider.getWorkManager() } returns workManager
        every {
            workManager.enqueueUniquePeriodicWork(any(), any(), any<PeriodicWorkRequest>())
        } returns immediateSuccessfulOperation()
    }

    @Test
    fun schedule_expectKeepPolicyAtTheFifteenMinuteFloor() {
        val requests = mutableListOf<PeriodicWorkRequest>()
        every {
            workManager.enqueueUniquePeriodicWork(any(), any(), capture(requests))
        } returns immediateSuccessfulOperation()

        WorkManagerPolygonRecheckScheduler(workManagerProvider).schedule() shouldBeEqualTo true

        // KEEP, not REPLACE: every sync calls this, and REPLACE would re-anchor the period each time.
        verify {
            workManager.enqueueUniquePeriodicWork(
                WorkManagerPolygonRecheckScheduler.POLYGON_RECHECK_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                any<PeriodicWorkRequest>()
            )
        }
        requests.single().workSpec.intervalDuration shouldBeEqualTo
            WorkManagerPolygonRecheckScheduler.RECHECK_INTERVAL_MINUTES * 60_000L
    }

    @Test
    fun schedule_givenWorkManagerUnavailable_expectFalseAndNoCrash() {
        every { workManagerProvider.getWorkManager() } returns null

        val scheduler = WorkManagerPolygonRecheckScheduler(workManagerProvider)

        scheduler.schedule() shouldBeEqualTo false
        scheduler.cancel() shouldBeEqualTo false
    }

    @Test
    fun reconcile_givenPolygonsRegistered_expectScheduledEvenWithNoneActive() {
        // Keyed on registered, not active: the re-check exists for the polygon that never woke.
        val scheduler = RecordingRecheckScheduler()
        every { mockStore.getActivePolygonIds() } returns emptySet()

        controller(scheduler).reconcileRegisteredPolygons(setOf("venue"))

        scheduler.calls shouldBeEqualTo listOf("schedule")
    }

    @Test
    fun reconcile_givenNoPolygonsRegistered_expectCancelled() {
        val scheduler = RecordingRecheckScheduler()
        every { mockStore.getActivePolygonIds() } returns emptySet()

        controller(scheduler).reconcileRegisteredPolygons(emptySet())

        scheduler.calls shouldBeEqualTo listOf("cancel")
    }

    @Test
    fun worker_givenNothingRegistered_expectItRetiresItselfWithoutAskingForAFix() = runTest {
        every { mockStore.getRoutableRegisteredIds() } returns emptySet()
        every { mockStore.getCachedRegions() } returns emptyList()

        val result = TestListenableWorkerBuilder<PolygonRecheckWorker>(applicationMock)
            .build()
            .doWork()

        result shouldBeEqualTo ListenableWorker.Result.success()
        verify { mockRecheckScheduler.cancel() }
        // No re-assert when the catalog really is empty, or the retirement would undo itself.
        verify(exactly = 0) { mockRecheckScheduler.schedule() }
        coVerify(exactly = 0) { mockFreshFix.awaitFreshFix(any(), any()) }
    }

    @Test
    fun worker_givenPermissionRevoked_expectItSaysSoRatherThanBlamingReception() = runTest {
        // awaitFreshFix returns the same null for a revoked permission and a silent GPS.
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())

        val result = TestListenableWorkerBuilder<PolygonRecheckWorker>(applicationMock)
            .build()
            .doWork()

        result shouldBeEqualTo ListenableWorker.Result.success()
        coVerify(exactly = 0) { mockFreshFix.awaitFreshFix(any(), any()) }
    }

    @Test
    fun worker_givenNoFixArrives_expectNothingActivated() = runTest {
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        coEvery { mockFreshFix.awaitFreshFix(any(), any()) } returns null

        val result = TestListenableWorkerBuilder<PolygonRecheckWorker>(applicationMock)
            .build()
            .doWork()

        result shouldBeEqualTo ListenableWorker.Result.success()
        coVerify(exactly = 0) { mockController.activate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun worker_givenTheFixIsInsideTheWakeCircle_expectTheOrdinaryCoarseEnterPath() = runTest {
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        val fix = fixAt(VENUE_LAT, VENUE_LNG)
        // An identify lands during the wait, so the stub moves the generation as the real store would.
        var generation = 7L
        every { mockStore.userStateGeneration() } answers { generation }
        coEvery { mockFreshFix.awaitFreshFix(any(), any()) } coAnswers {
            generation = 8L
            fix
        }

        val result = TestListenableWorkerBuilder<PolygonRecheckWorker>(applicationMock)
            .build()
            .doWork()

        result shouldBeEqualTo ListenableWorker.Result.success()
        // The generation from before the await, so the controller refuses it; the one current when
        // the fix landed would attribute this wake to the newly identified user.
        coVerify {
            mockController.activate(
                polygonId = VENUE_ID,
                triggeringLocation = fix,
                expectedUserStateGeneration = 7L,
                expectedRegionRevision = null,
                expectedTeardownGeneration = any()
            )
        }
        coVerify(exactly = 0) {
            mockController.activate(any(), any(), expectedUserStateGeneration = 8L, any(), any())
        }
    }

    @Test
    fun worker_expectItAsksForACheapFixNotAGpsOne() = runTest {
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        coEvery { mockFreshFix.awaitFreshFix(any(), any()) } returns null

        TestListenableWorkerBuilder<PolygonRecheckWorker>(applicationMock).build().doWork()

        coVerify { mockFreshFix.awaitFreshFix(any(), PolygonFixPriority.BALANCED) }
        coVerify(exactly = 0) {
            mockFreshFix.awaitFreshFix(any(), PolygonFixPriority.HIGH_ACCURACY)
        }
    }

    @Test
    fun worker_givenTheFixIsOutsideTheWakeCircle_expectNoActivation() = runTest {
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        coEvery { mockFreshFix.awaitFreshFix(any(), any()) } returns fixAt(VENUE_LAT + 1.0, VENUE_LNG)

        TestListenableWorkerBuilder<PolygonRecheckWorker>(applicationMock)
            .build()
            .doWork() shouldBeEqualTo ListenableWorker.Result.success()

        coVerify(exactly = 0) { mockController.activate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun worker_givenANonPolygonIsRegistered_expectItIsNotRecheckedAsOne() = runTest {
        every { mockStore.getRoutableRegisteredIds() } returns setOf("circle")
        every { mockStore.getCachedRegions() } returns listOf(
            venueRegion().copy(id = "circle", polygonVertices = null)
        )

        TestListenableWorkerBuilder<PolygonRecheckWorker>(applicationMock)
            .build()
            .doWork() shouldBeEqualTo ListenableWorker.Result.success()

        verify { mockRecheckScheduler.cancel() }
        coVerify(exactly = 0) { mockController.activate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun teardown_expectEveryPathThatDisablesGeofencingCancelsTheWork() {
        // A new teardown path on the controller belongs in this list or in the test below it.
        val paths = listOf<Pair<String, (PolygonGeofenceServiceController) -> Unit>>(
            "invalidateOsRegistrationState" to { it.invalidateOsRegistrationState() },
            "stopAll" to { it.stopAll() },
            "clearUserScopedState" to { it.clearUserScopedState() },
            "clearUserSessionRetainingOsRegistrations" to {
                it.clearUserSessionRetainingOsRegistrations()
            },
            "completeUserReset" to { it.completeUserReset(7L, osRegistrationsCleared = true) }
        )

        val observed = paths.associate { (name, teardown) ->
            val scheduler = RecordingRecheckScheduler()
            teardown(controller(scheduler))
            name to scheduler.calls.toList()
        }

        // Compared as a map so a failure names the path that leaked rather than a bare count.
        observed shouldBeEqualTo paths.associate { (name, _) -> name to listOf("cancel") }
    }

    @Test
    fun teardown_givenRegistrationsSurvive_expectTheWorkIsLeftAlone() {
        val scheduler = RecordingRecheckScheduler()

        controller(scheduler).invalidatePersistedCoarseState()

        scheduler.calls shouldBeEqualTo emptyList()
    }

    @Test
    fun teardown_expectTheCancelIsNotIssuedUnderTheControllerLock() {
        lateinit var subject: PolygonGeofenceServiceController
        var heldLockDuringCancel: Boolean? = null
        val scheduler = object : PolygonRecheckScheduler {
            override fun schedule(): Boolean = true

            override fun cancel(): Boolean {
                heldLockDuringCancel = subject.holdsControllerLock()
                return true
            }
        }
        subject = controller(scheduler)

        subject.stopAll()

        heldLockDuringCancel shouldBeEqualTo false
    }

    @Test
    fun worker_expectAdmissionIsPinnedToTheWakeCircleRadius() = runTest {
        // The venue circle is 120 m, so 110 m out must admit and 130 m must not.
        grantLocationPermission()
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        every { mockStore.getActivePolygonIds() } returns emptySet()
        val justInside = fixNorthOfVenue(110.0, accuracyMeters = 1.0f)
        val justOutside = fixNorthOfVenue(130.0, accuracyMeters = 1.0f)
        coEvery { mockFreshFix.awaitFreshFix(any(), any()) } returnsMany
            listOf(justInside, justOutside)

        TestListenableWorkerBuilder<PolygonRecheckWorker>(applicationMock).build().doWork()
        TestListenableWorkerBuilder<PolygonRecheckWorker>(applicationMock).build().doWork()

        // Identified by which fix reached activate, so neither run can stand in for the other.
        coVerify(exactly = 1) { mockController.activate(VENUE_ID, justInside, any(), any(), any()) }
        coVerify(exactly = 0) { mockController.activate(VENUE_ID, justOutside, any(), any(), any()) }
    }

    @Test
    fun worker_givenAnActivePolygonIsDecisivelyOutside_expectASyntheticCoarseExit() = runTest {
        grantLocationPermission()
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        every { mockStore.getActivePolygonIds() } returns setOf(VENUE_ID)
        val fix = fixNorthOfVenue(300.0, accuracyMeters = 20.0f)
        coEvery { mockFreshFix.awaitFreshFix(any(), any()) } returns fix

        TestListenableWorkerBuilder<PolygonRecheckWorker>(applicationMock).build().doWork()

        // onCoarseExit, not the store flag or a direct deactivate: it keeps a committed arrival active.
        coVerify(exactly = 1) {
            mockController.onCoarseExit(
                polygonId = VENUE_ID,
                triggeringLocation = fix,
                expectedUserStateGeneration = 7L,
                expectedRegionRevision = null,
                expectedTeardownGeneration = any()
            )
        }
        coVerify(exactly = 0) { mockController.activate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun worker_expectTheDepartureThresholdIsPinnedToTheRadius() = runTest {
        // Ten metres either side of the 120 m circle: 150 - 20 = 130 clears it, 130 - 20 = 110 does not.
        grantLocationPermission()
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        every { mockStore.getActivePolygonIds() } returns setOf(VENUE_ID)
        val clears = fixNorthOfVenue(150.0, accuracyMeters = 20.0f)
        val doesNotClear = fixNorthOfVenue(130.0, accuracyMeters = 20.0f)
        coEvery { mockFreshFix.awaitFreshFix(any(), any()) } returnsMany listOf(clears, doesNotClear)

        TestListenableWorkerBuilder<PolygonRecheckWorker>(applicationMock).build().doWork()
        TestListenableWorkerBuilder<PolygonRecheckWorker>(applicationMock).build().doWork()

        coVerify(exactly = 1) { mockController.onCoarseExit(VENUE_ID, clears, any(), any(), any()) }
        coVerify(exactly = 0) {
            mockController.onCoarseExit(VENUE_ID, doesNotClear, any(), any(), any())
        }
    }

    @Test
    fun worker_givenTheFixReportsNoAccuracy_expectNoClear() = runTest {
        // accuracy is 0 when unset, which would make the margin vanish.
        grantLocationPermission()
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        every { mockStore.getActivePolygonIds() } returns setOf(VENUE_ID)
        coEvery { mockFreshFix.awaitFreshFix(any(), any()) } returns Location("test").apply {
            latitude = VENUE_LAT + 300.0 / METRES_PER_DEGREE_LATITUDE
            longitude = VENUE_LNG
            elapsedRealtimeNanos = 5_000_000_000L
        }

        TestListenableWorkerBuilder<PolygonRecheckWorker>(applicationMock).build().doWork()

        coVerify(exactly = 0) { mockController.onCoarseExit(any(), any(), any(), any(), any()) }
    }

    @Test
    fun worker_givenTheOutsideFixIsTooCoarseToDecide_expectNoClear() = runTest {
        // 300 m out with 250 m of error does not establish a departure.
        grantLocationPermission()
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        every { mockStore.getActivePolygonIds() } returns setOf(VENUE_ID)
        coEvery { mockFreshFix.awaitFreshFix(any(), any()) } returns
            fixNorthOfVenue(300.0, accuracyMeters = 250.0f)

        TestListenableWorkerBuilder<PolygonRecheckWorker>(applicationMock).build().doWork()

        coVerify(exactly = 0) { mockController.onCoarseExit(any(), any(), any(), any(), any()) }
    }

    @Test
    fun worker_givenTheOutsidePolygonWasNeverActive_expectNothingToClear() = runTest {
        grantLocationPermission()
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        every { mockStore.getActivePolygonIds() } returns emptySet()
        coEvery { mockFreshFix.awaitFreshFix(any(), any()) } returns
            fixNorthOfVenue(300.0, accuracyMeters = 20.0f)

        TestListenableWorkerBuilder<PolygonRecheckWorker>(applicationMock).build().doWork()

        coVerify(exactly = 0) { mockController.onCoarseExit(any(), any(), any(), any(), any()) }
    }

    @Test
    fun worker_expectTheTeardownTokenIsReadBeforeTheWaitNotAfterIt() = runTest {
        // The stub moves the token during the wait, the way a teardown would.
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        val fix = fixAt(VENUE_LAT, VENUE_LNG)
        var teardowns = 3L
        every { mockController.teardownGeneration() } answers { teardowns }
        coEvery { mockFreshFix.awaitFreshFix(any(), any()) } coAnswers {
            teardowns = 4L
            fix
        }

        TestListenableWorkerBuilder<PolygonRecheckWorker>(applicationMock).build().doWork()

        coVerify {
            mockController.activate(
                polygonId = VENUE_ID,
                triggeringLocation = fix,
                expectedUserStateGeneration = any(),
                expectedRegionRevision = null,
                expectedTeardownGeneration = 3L
            )
        }
        coVerify(exactly = 0) {
            mockController.activate(any(), any(), any(), any(), expectedTeardownGeneration = 4L)
        }
    }

    @Test
    fun activate_givenATeardownSinceTheTokenWasTaken_expectNoReArm() = runTest {
        // stopAll() leaves the routable ids and the user generation in place, so only the token refuses.
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        every { mockStore.getCachedRegion(VENUE_ID) } returns venueRegion()
        every { mockStore.activeUserSessionId() } returns "user-1"
        val controller = controller(mockRecheckScheduler)
        val tokenBeforeTeardown = controller.teardownGeneration()

        controller.stopAll()
        controller.activate(
            polygonId = VENUE_ID,
            expectedUserStateGeneration = 7L,
            expectedRegionRevision = null,
            expectedTeardownGeneration = tokenBeforeTeardown
        )

        verify(exactly = 0) { mockStore.activatePolygon(VENUE_ID) }
    }

    @Test
    fun activate_givenNoTeardownSinceTheTokenWasTaken_expectItStillArms() = runTest {
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        every { mockStore.getCachedRegion(VENUE_ID) } returns venueRegion()
        every { mockStore.activeUserSessionId() } returns "user-1"
        val controller = controller(mockRecheckScheduler)

        controller.activate(
            polygonId = VENUE_ID,
            expectedUserStateGeneration = 7L,
            expectedRegionRevision = null,
            expectedTeardownGeneration = controller.teardownGeneration()
        )

        verify { mockStore.activatePolygon(VENUE_ID) }
    }

    @Test
    fun worker_givenTheCatalogFillsDuringRetirement_expectTheScheduleIsReasserted() = runTest {
        // A sync writes the catalog between this run's empty read and its re-read, so the retirement
        // must not cancel the schedule that sync made.
        var reads = 0
        every { mockStore.getRoutableRegisteredIds() } answers {
            reads++
            if (reads == 1) emptySet() else setOf(VENUE_ID)
        }
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())

        val result = TestListenableWorkerBuilder<PolygonRecheckWorker>(applicationMock)
            .build()
            .doWork()

        result shouldBeEqualTo ListenableWorker.Result.success()
        reads shouldBeEqualTo 2
        // Order, not counts: a cancel after the re-assert would leave nothing scheduled.
        verifyOrder {
            mockRecheckScheduler.cancel()
            mockRecheckScheduler.schedule()
        }
        verify(exactly = 1) { mockRecheckScheduler.schedule() }
        // No fix: this run read an empty catalog.
        coVerify(exactly = 0) { mockFreshFix.awaitFreshFix(any(), any()) }
    }

    @Test
    fun worker_expectTheOwnershipSnapshotIsTakenBeforeTheCatalogReads() = runTest {
        // The stubs move token and generation on the first catalog read, so the snapshot must
        // precede it.
        var teardowns = 3L
        var generation = 7L
        every { mockController.teardownGeneration() } answers { teardowns }
        every { mockStore.userStateGeneration() } answers { generation }
        every { mockStore.getRoutableRegisteredIds() } answers {
            teardowns = 4L
            generation = 8L
            setOf(VENUE_ID)
        }
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        val fix = fixAt(VENUE_LAT, VENUE_LNG)
        coEvery { mockFreshFix.awaitFreshFix(any(), any()) } returns fix

        TestListenableWorkerBuilder<PolygonRecheckWorker>(applicationMock).build().doWork()

        coVerify {
            mockController.activate(
                polygonId = VENUE_ID,
                triggeringLocation = fix,
                expectedUserStateGeneration = 7L,
                expectedRegionRevision = null,
                expectedTeardownGeneration = 3L
            )
        }
        coVerify(exactly = 0) {
            mockController.activate(any(), any(), any(), any(), expectedTeardownGeneration = 4L)
        }
        coVerify(exactly = 0) {
            mockController.activate(
                polygonId = any(),
                triggeringLocation = any(),
                expectedUserStateGeneration = 8L,
                expectedRegionRevision = any(),
                expectedTeardownGeneration = any()
            )
        }
    }

    @Test
    fun activate_givenATeardownLandsAfterTheEarlyCheck_expectTheLockedActivationRefuses() = runTest {
        // The teardown runs inside the first registration read, between the early token check and the
        // locked activation. Not a lock-freedom fixture: stopAll runs reentrantly under this lock.
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        every { mockStore.activeUserSessionId() } returns "user-1"
        var subject: PolygonGeofenceServiceController? = null
        var tornDown = false
        every { mockStore.getCachedRegion(VENUE_ID) } answers {
            if (!tornDown) {
                tornDown = true
                subject?.stopAll()
            }
            venueRegion()
        }
        val mockEngine: PolygonLocationEngine = mockk(relaxed = true)
        val controller = controller(mockRecheckScheduler, mockEngine).also { subject = it }
        val token = controller.teardownGeneration()

        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = fixAt(VENUE_LAT, VENUE_LNG),
            expectedUserStateGeneration = 7L,
            expectedRegionRevision = null,
            expectedTeardownGeneration = token
        )

        tornDown shouldBeEqualTo true
        verify(exactly = 0) { mockStore.activatePolygon(VENUE_ID) }
        coVerify(exactly = 0) { mockEngine.processResponsiveLocation(any(), any()) }
    }

    @Test
    fun onCoarseExit_givenATeardownSinceTheTokenWasTaken_expectNoReArm() = runTest {
        // Teardown retains the entered set, which the departure path uses to keep a polygon active.
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegion(VENUE_ID) } returns venueRegion()
        every { mockStore.activeUserSessionId() } returns "user-1"
        every { mockStore.getCoarseInsidePolygonIds() } returns emptySet()
        every { mockStore.getEnteredIds() } returns setOf(VENUE_ID)
        val controller = controller(mockRecheckScheduler)
        val tokenBeforeTeardown = controller.teardownGeneration()

        controller.stopAll()
        controller.onCoarseExit(
            polygonId = VENUE_ID,
            triggeringLocation = fixNorthOfVenue(300.0, accuracyMeters = 20.0f),
            expectedUserStateGeneration = 7L,
            expectedRegionRevision = null,
            expectedTeardownGeneration = tokenBeforeTeardown
        )

        verify(exactly = 0) { mockStore.activatePolygon(VENUE_ID) }
        verify(exactly = 0) { mockStore.recordPolygonCoarseOutside(VENUE_ID) }
    }

    @Test
    fun onCoarseExit_givenATeardownLandsBeforeTheWrite_expectNoCoarseStateAndNoFixRequest() =
        runTest {
            // The teardown runs inside the pre-lock registration read, so it lands after the early
            // bail and only the locked check can refuse.
            every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
            every { mockStore.activeUserSessionId() } returns "user-1"
            every { mockStore.getCoarseInsidePolygonIds() } returns emptySet()
            every { mockStore.getEnteredIds() } returns setOf(VENUE_ID)
            var subject: PolygonGeofenceServiceController? = null
            var tornDown = false
            every { mockStore.getCachedRegion(VENUE_ID) } answers {
                if (!tornDown) {
                    tornDown = true
                    subject?.stopAll()
                }
                venueRegion()
            }
            val mockEngine: PolygonLocationEngine = mockk(relaxed = true)
            val controller = controller(mockRecheckScheduler, mockEngine).also { subject = it }
            val token = controller.teardownGeneration()

            controller.onCoarseExit(
                polygonId = VENUE_ID,
                triggeringLocation = fixNorthOfVenue(300.0, accuracyMeters = 20.0f),
                expectedUserStateGeneration = 7L,
                expectedRegionRevision = null,
                expectedTeardownGeneration = token
            )

            tornDown shouldBeEqualTo true
            verify(exactly = 0) { mockStore.recordPolygonCoarseOutside(VENUE_ID) }
            coVerify(exactly = 0) { mockEngine.processResponsiveLocation(any(), any()) }
            verify(exactly = 0) { mockStore.activatePolygon(VENUE_ID) }
        }

    @Test
    fun onCoarseExit_givenATeardownLandsWhileTheFixIsEvaluated_expectNoReArm() = runTest {
        // Driven from the evaluation: evaluateCallbackFix suspends, and the tail runs after it.
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegion(VENUE_ID) } returns venueRegion()
        every { mockStore.activeUserSessionId() } returns "user-1"
        every { mockStore.getCoarseInsidePolygonIds() } returns emptySet()
        every { mockStore.getEnteredIds() } returns setOf(VENUE_ID)
        var subject: PolygonGeofenceServiceController? = null
        var tornDown = false
        val mockEngine: PolygonLocationEngine = mockk(relaxed = true)
        coEvery { mockEngine.processResponsiveLocation(any(), any()) } coAnswers {
            if (!tornDown) {
                tornDown = true
                subject?.stopAll()
            }
            PolygonEvaluationOutcome.NOTHING
        }
        val controller = controller(mockRecheckScheduler, mockEngine).also { subject = it }
        val token = controller.teardownGeneration()

        controller.onCoarseExit(
            polygonId = VENUE_ID,
            triggeringLocation = fixNorthOfVenue(300.0, accuracyMeters = 20.0f),
            expectedUserStateGeneration = 7L,
            expectedRegionRevision = null,
            expectedTeardownGeneration = token
        )

        tornDown shouldBeEqualTo true
        verify(exactly = 0) { mockStore.activatePolygon(VENUE_ID) }
    }

    @Test
    fun onCoarseExit_givenNoTeardown_expectACommittedArrivalStaysActive() = runTest {
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegion(VENUE_ID) } returns venueRegion()
        every { mockStore.activeUserSessionId() } returns "user-1"
        every { mockStore.getCoarseInsidePolygonIds() } returns emptySet()
        every { mockStore.getEnteredIds() } returns setOf(VENUE_ID)
        val controller = controller(mockRecheckScheduler)

        controller.onCoarseExit(
            polygonId = VENUE_ID,
            triggeringLocation = fixNorthOfVenue(300.0, accuracyMeters = 20.0f),
            expectedUserStateGeneration = 7L,
            expectedRegionRevision = null,
            expectedTeardownGeneration = controller.teardownGeneration()
        )

        verify { mockStore.activatePolygon(VENUE_ID) }
    }

    @Test
    fun worker_expectTheTeardownTokenReachesTheDeparturePathToo() = runTest {
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        every { mockStore.getActivePolygonIds() } returns setOf(VENUE_ID)
        every { mockController.teardownGeneration() } returns 3L
        val fix = fixNorthOfVenue(300.0, accuracyMeters = 20.0f)
        coEvery { mockFreshFix.awaitFreshFix(any(), any()) } returns fix

        TestListenableWorkerBuilder<PolygonRecheckWorker>(applicationMock).build().doWork()

        coVerify {
            mockController.onCoarseExit(
                polygonId = VENUE_ID,
                triggeringLocation = fix,
                expectedUserStateGeneration = any(),
                expectedRegionRevision = null,
                expectedTeardownGeneration = 3L
            )
        }
    }

    private fun controller(
        scheduler: PolygonRecheckScheduler,
        engine: PolygonLocationEngine = mockk(relaxed = true)
    ) = PolygonGeofenceServiceController(
        context = applicationMock,
        store = mockStore,
        engine = engine,
        approachMonitor = mockk(relaxed = true),
        manager = mockk(relaxed = true),
        secureUserStore = mockSecureUserStore,
        freshFixSource = NeverAnswersFreshFix,
        recheckScheduler = scheduler,
        passiveMonitor = NoopPassiveMonitor,
        bootSessionProvider = { "boot" },
        logger = mockk(relaxed = true)
    )

    private fun venueRegion() = GeofenceRegion(
        id = VENUE_ID,
        latitude = VENUE_LAT,
        longitude = VENUE_LNG,
        radius = 120.0f,
        polygonVertices = listOf(
            PolygonCoordinate(VENUE_LAT - 0.0005, VENUE_LNG - 0.0005),
            PolygonCoordinate(VENUE_LAT - 0.0005, VENUE_LNG + 0.0005),
            PolygonCoordinate(VENUE_LAT + 0.0005, VENUE_LNG + 0.0005),
            PolygonCoordinate(VENUE_LAT + 0.0005, VENUE_LNG - 0.0005)
        )
    )

    private fun fixAt(latitude: Double, longitude: Double) = Location("test").apply {
        this.latitude = latitude
        this.longitude = longitude
        accuracy = 12.0f
        elapsedRealtimeNanos = 5_000_000_000L
    }

    /**
     * A fix [metres] due north of the venue centre. The fixed metres-per-degree is ~0.5% off at
     * this latitude (under a metre here), so tests stay ten metres clear of a boundary.
     */
    private fun fixNorthOfVenue(metres: Double, accuracyMeters: Float) = Location("test").apply {
        latitude = VENUE_LAT + metres / METRES_PER_DEGREE_LATITUDE
        longitude = VENUE_LNG
        accuracy = accuracyMeters
        elapsedRealtimeNanos = 5_000_000_000L
    }

    private fun grantLocationPermission() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
    }

    private fun immediateSuccessfulOperation(): Operation = mockk(relaxed = true) {
        every { result } returns Futures.immediateFuture(Operation.SUCCESS)
    }

    private companion object {
        const val METRES_PER_DEGREE_LATITUDE = 111_320.0
        const val VENUE_ID = "venue"
        const val VENUE_LAT = 24.0
        const val VENUE_LNG = 67.0
    }
}
