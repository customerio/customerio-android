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
        coVerify(exactly = 0) { mockController.activate(any(), any(), any(), any()) }
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
                expectedRegionRevision = null
            )
        }
        coVerify(exactly = 0) {
            mockController.activate(any(), any(), expectedUserStateGeneration = 8L, any())
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

        coVerify(exactly = 0) { mockController.activate(any(), any(), any(), any()) }
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
        coVerify(exactly = 0) { mockController.activate(any(), any(), any(), any()) }
    }

    private fun controller(scheduler: PolygonRecheckScheduler) = PolygonGeofenceServiceController(
        context = applicationMock,
        store = mockStore,
        engine = mockk(relaxed = true),
        approachMonitor = mockk(relaxed = true),
        manager = mockk(relaxed = true),
        secureUserStore = mockSecureUserStore,
        freshFixSource = NeverAnswersFreshFix,
        recheckScheduler = scheduler,
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
        const val VENUE_ID = "venue"
        const val VENUE_LAT = 24.0
        const val VENUE_LNG = 67.0
    }
}
