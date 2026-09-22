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
 * The periodic re-check, which exists for the wake that never happens.
 *
 * A polygon is only ever evaluated because its wake circle reported a crossing. GMS re-reports ENTER
 * for a fence added around a device already inside one, but unreliably for a stationary device —
 * `emitInitialEnters` is the backstop for that, and it skips polygons. So a device sitting inside a
 * wake circle at registration can get no callback and no backstop, and nothing else re-derives a
 * polygon arrival. These tests pin the two halves: the work is scheduled exactly while polygons are
 * registered, and a run feeds its fix through the ordinary coarse-ENTER path.
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

        // KEEP, not REPLACE. Every sync calls this, and REPLACE would re-anchor the period on each
        // one, so an app that syncs more often than the interval would never re-check at all.
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
        // The host can leave WorkManager uninitialised; the callback path still works without this.
        every { workManagerProvider.getWorkManager() } returns null

        val scheduler = WorkManagerPolygonRecheckScheduler(workManagerProvider)

        scheduler.schedule() shouldBeEqualTo false
        scheduler.cancel() shouldBeEqualTo false
    }

    @Test
    fun reconcile_givenPolygonsRegistered_expectScheduledEvenWithNoneActive() {
        // Keyed on registered, not active. An active polygon is one already woken, and this exists
        // for the polygon that never woke, so gating on the active set would disable it in exactly
        // the case it is for.
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
        // Self-healing: a missed cancel site costs one wake rather than a permanent periodic job.
        verify { mockRecheckScheduler.cancel() }
        // And the re-assert stays out of it when the catalog really is empty, or the retirement
        // would put back exactly what it just removed.
        verify(exactly = 0) { mockRecheckScheduler.schedule() }
        coVerify(exactly = 0) { mockFreshFix.awaitFreshFix(any(), any()) }
    }

    @Test
    fun worker_givenPermissionRevoked_expectItSaysSoRatherThanBlamingReception() = runTest {
        // The worker runs while the app is backgrounded, which is when a revocation is most likely.
        // awaitFreshFix reports a revoked permission and a silent GPS as the same null, so without
        // this check a capture reads a permission loss as poor reception for as long as it lasts.
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
        // An identify lands while the fix is being awaited, which is reachable: the wait is up to
        // 20 s. A constant generation here would let the read move after the await and nothing
        // would notice, so the stub moves the way the real store would.
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
        // The same entry point a GMS callback uses, carrying the generation from BEFORE the await
        // so the controller refuses it, rather than the generation current when the fix landed,
        // which would attribute this wake to the newly identified user.
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
        // The cost of this mechanism. Registered polygons are only the nearest set, so a user
        // kilometres from all of them still gets woken every interval; asking for GPS-grade
        // accuracy to answer "inside a circle hundreds of metres across" would spend the battery
        // for nothing. Precision is bought downstream, and only when the device is actually inside.
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
        // Registered but far away: there is nothing to re-check, and waking the evaluator for it
        // would start an approach session for a polygon the device is nowhere near.
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
        // Circles have their own initial-ENTER synthesis; re-checking them here would double-report.
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
        // Enumerated, not sampled. When this landed, the scheduler reached exactly one call site
        // while approachMonitor.stop reached eleven, so the periodic work outlived every teardown
        // but one and kept fetching fixes after geofencing was stopped. A new teardown path on this
        // class belongs in this list or in the test below it.
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
        // The control for the test above. invalidatePersistedCoarseState wipes coarse and active
        // state but deliberately keeps registrations, so the polygons are still registered and the
        // re-check is the one thing left that can re-derive an arrival GMS never reported.
        // Cancelling here would disable it in exactly the case it exists for.
        val scheduler = RecordingRecheckScheduler()

        controller(scheduler).invalidatePersistedCoarseState()

        scheduler.calls shouldBeEqualTo emptyList()
    }

    @Test
    fun teardown_expectTheCancelIsNotIssuedUnderTheControllerLock() {
        // cancel() reaches WorkManager, which does disk work, and the scheduler logs through the
        // host dispatcher, which is customer code. Issuing it under controllerLock would stall
        // every coarse callback behind that dispatcher.
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
        // This replaces a fixture that put the fix at the exact centre and its only negative case
        // ~111 km away, which asserted nothing about the radius: a build that halved the wake
        // circle passed it. The venue circle is 120 m, so 110 m out must admit and 130 m must not.
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
        // activate() records the polygon coarse-inside and only a GMS coarse EXIT clears it. If
        // this worker recovered an ENTER that GMS never issued, GMS holds no inside state for the
        // fence and may issue no EXIT, so without this the polygon stays active for the life of
        // the install and every fix reaching the engine re-evaluates it.
        grantLocationPermission()
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        every { mockStore.getActivePolygonIds() } returns setOf(VENUE_ID)
        val fix = fixNorthOfVenue(300.0, accuracyMeters = 20.0f)
        coEvery { mockFreshFix.awaitFreshFix(any(), any()) } returns fix

        TestListenableWorkerBuilder<PolygonRecheckWorker>(applicationMock).build().doWork()

        // onCoarseExit, not the store flag and not a direct deactivate: it is the path that keeps
        // a committed arrival active and reports the holds a teardown discarded.
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
        // The decisively-outside test left the threshold unpinned: 300 m out with 20 m error
        // clears a 120 m circle and would equally clear a 240 m one, so a mutant that doubled the
        // radius survived it. Ten metres either side pins the subtraction: 150 - 20 = 130 clears
        // 120, and 130 - 20 = 110 does not.
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
        // accuracy is 0 when unset, which would make the margin vanish and clear a live session on
        // a fix whose error is unknown.
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
        // The guard against re-introducing a phantom EXIT. 300 m out with 250 m of error does not
        // establish a departure, and tearing a live session down on it is the failure this
        // component already paid for three times.
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
        // The control for the two above. A registered polygon we never activated has no coarse
        // state to clear, and calling the exit path for it would churn the dedupe memo and put a
        // departure in the capture for a polygon that was never arrived at.
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
        // The read has to happen before awaitFreshFix, or it cannot distinguish a session that
        // ended during the wait. A stub that moves the way a teardown would is what pins that: if
        // the worker read it after the fix landed it would hand over the current value and the
        // controller would accept a wake its session no longer owns.
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
        // Raised by Shahroz on #896. stopAll() leaves the routable ids and the user generation in
        // place on purpose, so a worker already past its wait passed both checks and re-armed the
        // polygon it had just torn down. Cancellation cannot close this: the coroutine may already
        // be past the suspension point when the teardown lands.
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
        // The control. A token that is still current must not block an ordinary scheduled wake,
        // or the fix above would be an off switch for the whole mechanism.
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
        // Raised by Shahroz on #896 with a reproduction: a sync scheduled the unique periodic work
        // after this run read an empty catalog, and the retirement cancelled that newer schedule.
        // His run expected [schedule] and got [schedule, cancel]. What is left behind is no
        // periodic re-check until another sync, which is the stationary-device gap this worker is
        // for.
        //
        // Fixed by ordering rather than by narrowing: cancel, re-read, and re-assert if the
        // catalog filled. Modelled as a catalog that is empty on the first read and populated on
        // the re-read, which is the interleaving where the sync's write beat the re-read.
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
        // Order, not counts: cancelling after the re-assert would leave nothing scheduled and a
        // count assertion would pass either way.
        verifyOrder {
            mockRecheckScheduler.cancel()
            mockRecheckScheduler.schedule()
        }
        verify(exactly = 1) { mockRecheckScheduler.schedule() }
        // Still asks for no fix: this run read an empty catalog and has nothing to re-check.
        coVerify(exactly = 0) { mockFreshFix.awaitFreshFix(any(), any()) }
    }

    @Test
    fun worker_expectTheOwnershipSnapshotIsTakenBeforeTheCatalogReads() = runTest {
        // Raised by Shahroz on #896 with a reproduction: the token was read after the routable
        // catalog and the permission check, so a teardown landing during that work was captured as
        // though it had always been current. The run then handed over the post-teardown token,
        // matched it, and re-armed the polygon teardown had just removed.
        //
        // Both halves of the snapshot are pinned here, because the user generation was read late
        // for the same reason and carries the same defect: a catalog read for the outgoing user
        // attributed to the incoming one. Stubs that move the way a teardown and an identify would,
        // driven from the FIRST store read, so the capture has to precede it.
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
        // Raised by Shahroz on #896, reproduced on the latest head. The suspend entry checked the
        // token and then called the locked activation without it. The registration and dedupe reads
        // in between take and release the lock, so a teardown landing in that gap met no further
        // check and the polygon went back into the active set.
        //
        // The teardown is driven from the first registration read, which is the gap itself, so the
        // interleaving is deterministic rather than raced. A real teardown on another thread would
        // block on the lock instead of running inside that read, but for the check this defeats
        // the two are indistinguishable: the generation has moved before the lock is retaken. Not
        // a lock-freedom fixture, though: under reentrancy stopAll's reporting runs while this
        // caller still holds the lock, so read it only for the assertion it makes.
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
        // A refused activation must not go on to judge its fix either, or the polygon it was
        // refused for could still have an arrival committed against it.
        coVerify(exactly = 0) { mockEngine.processResponsiveLocation(any(), any()) }
    }

    @Test
    fun onCoarseExit_givenATeardownSinceTheTokenWasTaken_expectNoReArm() = runTest {
        // Raised by Shahroz on #896. The departure path re-arms: a polygon still in the entered set
        // is kept active instead of being deactivated. Teardown retains the entered set on purpose,
        // so a scheduled departure dispatched before stopAll() and run after it put the polygon
        // back and restarted approach sampling, which is the teardown undone by its own exit path.
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
        // Refused before it records anything. Teardown retains the registrations, so without a
        // token check this dead departure passes the registration check, records coarse-outside,
        // and reaches evaluateCallbackFix, which can open the GPS for a finished session.
        verify(exactly = 0) { mockStore.recordPolygonCoarseOutside(VENUE_ID) }
    }

    @Test
    fun onCoarseExit_givenATeardownLandsBeforeTheWrite_expectNoCoarseStateAndNoFixRequest() =
        runTest {
            // Raised by Bugbot on #896 once the re-arm was guarded: the tail check leaves the
            // write and the fix evaluation before it unguarded, so a departure whose session ended
            // mid-pass still recorded coarse state and could open the GPS for it.
            //
            // The teardown is driven from the pre-lock registration read, so it lands after the
            // cheap bail has already passed and the locked check is the only one left.
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
        // The window the check above cannot cover. evaluateCallbackFix suspends, so a teardown
        // that was not yet current when the coarse-outside write happened can be current by the
        // time the tail decides whether to keep the polygon active. Driven from the evaluation
        // itself, which is exactly where the suspension is.
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
        // The control for the two above. The re-arm this guards is the correct behaviour when no
        // teardown happened: an already-committed arrival keeps its session so the business EXIT
        // has something to run against. Without this, the guard could be an off switch for the
        // whole departure path and both tests above would still pass.
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
        // Forwarding the token to activate() alone fixed the arrival half and left the departure
        // half able to undo a teardown, so the worker has to hand the same token to both.
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
     * A fix [metres] due north of the venue centre. Derived from a fixed metres-per-degree, which
     * is ~0.4% off at this latitude, so it is accurate to well under a metre over these distances
     * but not exact: the tests below stay ten metres clear of the boundary for that reason.
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
