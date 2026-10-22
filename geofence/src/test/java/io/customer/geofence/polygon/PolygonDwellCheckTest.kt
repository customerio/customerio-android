package io.customer.geofence.polygon

import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.GeofenceConstants
import io.customer.geofence.GeofencePermissionChecker
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.store.GeofenceDwellReservation
import io.customer.geofence.store.GeofenceDwellVisit
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.transitionRevision
import io.customer.sdk.core.util.CustomerIOWorkManagerProvider
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PolygonDwellCheckTest : RobolectricTest() {
    private val store: GeofenceRegionStore = mockk(relaxed = true)
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val permissionChecker: GeofencePermissionChecker = mockk(relaxed = true)
    private val workManagerProvider: CustomerIOWorkManagerProvider = mockk(relaxed = true)
    private val workManager: WorkManager = mockk(relaxed = true)
    private val controller: PolygonGeofenceServiceController = mockk(relaxed = true)
    private val requests = mutableListOf<OneTimeWorkRequest>()
    private var nowMs = ENTERED_AT_MS
    private var visits = listOf(visit())

    private val planner by lazy {
        PolygonDwellCheckPlanner(store, secureUserStore, { BOOT }, permissionChecker)
    }
    private val scheduler by lazy {
        WorkManagerPolygonDwellCheckScheduler(workManagerProvider, planner) { nowMs }
    }

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
                diGraph {
                    android {
                        overrideDependency<PolygonDwellCheckScheduler>(scheduler)
                        overrideDependency<PolygonGeofenceServiceController>(controller)
                        overrideDependency<GeofenceRegionStore>(store)
                    }
                }
            }
        )
        every { permissionChecker.hasFineLocationPermission() } returns true
        every { secureUserStore.getUserId() } returns USER
        every { store.activeUserSessionId() } returns USER
        every { store.userStateGeneration() } returns GENERATION
        every { store.getRegisteredIds() } returns setOf(POLYGON.id)
        every { store.getRoutableRegisteredIds() } returns setOf(POLYGON.id)
        every { store.getEnteredIds() } returns setOf(POLYGON.id)
        every { store.getCachedRegion(POLYGON.id) } returns POLYGON
        every { store.getDwellVisits() } answers { visits }
        every { workManagerProvider.getWorkManager() } returns workManager
        every { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } answers {
            requests += thirdArg<OneTimeWorkRequest>()
            mockk(relaxed = true)
        }
        every { controller.teardownGeneration() } returns TEARDOWN
    }

    @Test
    fun pendingChecks_givenAFreshVisit_expectItsThresholdThenTheSpacingAfterItsLastRequest() {
        planner.pendingChecks() shouldBeEqualTo listOf(PolygonDwellCheck(POLYGON.id, VISIT, DUE_AT_MS))

        visits = listOf(visit().copy(dwellProofRequests = 1, lastDwellProofRequestElapsedMs = DUE_AT_MS + 5_000L))
        planner.pendingChecks().single().atElapsedMs shouldBeEqualTo
            DUE_AT_MS + 5_000L + GeofenceConstants.DWELL_PROOF_REQUEST_SPACING_MS

        // A request whose spacing ends before the threshold never makes a check earlier than the threshold.
        visits = listOf(
            visit().copy(
                dwellProofRequests = 1,
                lastDwellProofRequestElapsedMs = ENTERED_AT_MS - GeofenceConstants.DWELL_PROOF_REQUEST_SPACING_MS
            )
        )
        planner.pendingChecks().single().atElapsedMs shouldBeEqualTo DUE_AT_MS
    }

    @Test
    fun pendingChecks_givenAVisitNoCheckCouldProve_expectNone() {
        listOf(
            visit().copy(emitted = true),
            visit().copy(dwellReservation = GeofenceDwellReservation(1L, null, 60, null, "location_evidence")),
            visit().copy(dwellProofRequests = GeofenceConstants.MAX_DWELL_PROOF_REQUESTS_PER_VISIT),
            visit().copy(bootSessionId = "another-boot"),
            visit().copy(userStateGeneration = GENERATION + 1),
            visit().copy(regionRevision = POLYGON.transitionRevision() + 1),
            visit().copy(enteredAtElapsedMs = null)
        ).forEach { stale ->
            visits = listOf(stale)
            planner.pendingChecks().shouldBeEmpty()
        }
        visits = listOf(visit())

        every { store.getCachedRegion(POLYGON.id) } returns POLYGON.copy(dwellThresholdSeconds = 0)
        planner.pendingChecks().shouldBeEmpty()
        every { store.getCachedRegion(POLYGON.id) } returns POLYGON.copy(polygonVertices = null)
        planner.pendingChecks().shouldBeEmpty()
        every { store.getCachedRegion(POLYGON.id) } returns POLYGON

        every { store.getEnteredIds() } returns emptySet()
        planner.pendingChecks().shouldBeEmpty()
        every { store.getEnteredIds() } returns setOf(POLYGON.id)

        every { store.getRoutableRegisteredIds() } returns emptySet()
        planner.pendingChecks().shouldBeEmpty()
        every { store.getRoutableRegisteredIds() } returns setOf(POLYGON.id)

        every { store.activeUserSessionId() } returns "someone-else"
        planner.pendingChecks().shouldBeEmpty()
        every { store.activeUserSessionId() } returns USER

        // Without fine location no fix could prove the stay.
        every { permissionChecker.hasFineLocationPermission() } returns false
        planner.pendingChecks().shouldBeEmpty()
    }

    @Test
    fun reconcile_givenAPendingVisit_expectOneReplaceAtItsThresholdAndNoRepeatForTheSameTime() {
        nowMs = ENTERED_AT_MS + 10_000L

        scheduler.reconcile()
        scheduler.reconcile()

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(
                WorkManagerPolygonDwellCheckScheduler.POLYGON_DWELL_CHECK_WORK,
                ExistingWorkPolicy.REPLACE,
                any<OneTimeWorkRequest>()
            )
        }
        requests.single().workSpec.initialDelay shouldBeEqualTo DUE_AT_MS - nowMs
    }

    @Test
    fun reconcile_givenTheVisitSettled_expectTheJobCancelled() {
        scheduler.reconcile()
        visits = listOf(visit().copy(emitted = true))

        scheduler.reconcile()

        verify(exactly = 1) {
            workManager.cancelUniqueWork(WorkManagerPolygonDwellCheckScheduler.POLYGON_DWELL_CHECK_WORK)
        }
    }

    @Test
    fun run_givenReconcilesWhileRunning_expectOnlyTheRunsEndSchedules() {
        nowMs = DUE_AT_MS
        scheduler.beginRun()

        scheduler.takeDueChecks() shouldBeEqualTo listOf(PolygonDwellCheck(POLYGON.id, VISIT, DUE_AT_MS))
        scheduler.reconcile()
        verify(exactly = 0) { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }

        scheduler.endRun()

        // The check asked nothing durable, yet the next waits the spacing rather than running at once.
        requests.single().workSpec.initialDelay shouldBeEqualTo GeofenceConstants.DWELL_PROOF_REQUEST_SPACING_MS
    }

    @Test
    fun run_givenAnUnchangedTimeAfterTheJobRan_expectItEnqueuedAgain() {
        // The OS ran the job early; nothing was due, so the time it computes is the one it ran for.
        nowMs = ENTERED_AT_MS
        scheduler.reconcile()
        scheduler.beginRun()
        scheduler.takeDueChecks().shouldBeEmpty()
        scheduler.endRun()

        requests.size shouldBeEqualTo 2
    }

    @Test
    fun takeDueChecks_givenEveryAttemptRefusedWithoutACharge_expectAtMostTheBudgetThenCancel() {
        nowMs = DUE_AT_MS
        repeat(GeofenceConstants.MAX_DWELL_PROOF_REQUESTS_PER_VISIT) {
            scheduler.beginRun()
            scheduler.takeDueChecks().size shouldBeEqualTo 1
            scheduler.endRun()
            nowMs += GeofenceConstants.DWELL_PROOF_REQUEST_SPACING_MS
        }

        scheduler.takeDueChecks().shouldBeEmpty()
        verify { workManager.cancelUniqueWork(WorkManagerPolygonDwellCheckScheduler.POLYGON_DWELL_CHECK_WORK) }
    }

    @Test
    fun doWork_givenADueVisit_expectOneCheckWithTheTokensTakenFirstThenTheNextScheduled() = runTest {
        nowMs = DUE_AT_MS
        coEvery { controller.onDwellCheck(any(), any(), any(), any()) } answers {
            nowMs += 1_000L
            null
        }

        val result = TestListenableWorkerBuilder<PolygonDwellCheckWorker>(applicationMock).build().doWork()

        result shouldBeEqualTo ListenableWorker.Result.success()
        coVerify(exactly = 1) { controller.onDwellCheck(POLYGON.id, VISIT, GENERATION, TEARDOWN) }
        verifyOrder {
            controller.teardownGeneration()
            workManager.enqueueUniqueWork(any(), ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>())
        }
        TimeUnit.MILLISECONDS.toSeconds(requests.single().workSpec.initialDelay) shouldBeEqualTo
            TimeUnit.MILLISECONDS.toSeconds(GeofenceConstants.DWELL_PROOF_REQUEST_SPACING_MS - 1_000L)
    }

    @Test
    fun doWork_givenTheCheckRefusedByAnotherRequestsCooldown_expectARetryThatDoesNotSpendAnAttempt() = runTest {
        nowMs = DUE_AT_MS
        coEvery { controller.onDwellCheck(any(), any(), any(), any()) } returns 30_000L

        val uncounted = WorkManagerPolygonDwellCheckScheduler.MAX_UNCOUNTED_RETRIES_PER_VISIT
        val budget = GeofenceConstants.MAX_DWELL_PROOF_REQUESTS_PER_VISIT
        repeat(uncounted + budget) {
            TestListenableWorkerBuilder<PolygonDwellCheckWorker>(applicationMock).build().doWork()
            nowMs += 30_000L
        }

        // Refusals by another request's cooldown spend no attempt at first, so they cannot exhaust
        // this visit's checks; past the cap they count, so edge requests cannot renew it forever.
        coVerify(exactly = uncounted + budget) { controller.onDwellCheck(POLYGON.id, VISIT, any(), any()) }
        verify { workManager.cancelUniqueWork(WorkManagerPolygonDwellCheckScheduler.POLYGON_DWELL_CHECK_WORK) }
        scheduler.takeDueChecks().shouldBeEmpty()
    }

    @Test
    fun reconcile_givenAStalePlanRacingANewerOne_expectTheNewerJobKept() {
        // The first reconcile plans from a store with no visit and stalls there; meanwhile the visit
        // starts and a second reconcile runs. The stale one must not cancel the newer job.
        val stalePlanning = CountDownLatch(1)
        val releaseStale = CountDownLatch(1)
        var firstRead = true
        every { store.getDwellVisits() } answers {
            if (firstRead) {
                firstRead = false
                stalePlanning.countDown()
                releaseStale.await(5, TimeUnit.SECONDS)
                emptyList()
            } else {
                listOf(visit())
            }
        }
        val stale = Thread { scheduler.reconcile() }.apply { start() }
        stalePlanning.await(5, TimeUnit.SECONDS)
        val newer = Thread { scheduler.reconcile() }.apply { start() }
        Thread.sleep(200)
        releaseStale.countDown()
        stale.join(5_000)
        newer.join(5_000)

        verifyOrder {
            workManager.cancelUniqueWork(WorkManagerPolygonDwellCheckScheduler.POLYGON_DWELL_CHECK_WORK)
            workManager.enqueueUniqueWork(any(), ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>())
        }
        requests.size shouldBeEqualTo 1
    }

    @Test
    fun doWork_givenACheckThatThrows_expectTheRunStillEndsAndReschedules() = runTest {
        nowMs = DUE_AT_MS
        coEvery { controller.onDwellCheck(any(), any(), any(), any()) } throws IllegalStateException("boom")

        runCatching { TestListenableWorkerBuilder<PolygonDwellCheckWorker>(applicationMock).build().doWork() }

        // The run's end still schedules, so a later reconcile is not deferred forever.
        requests.size shouldBeEqualTo 1
        scheduler.reconcile()
        visits = emptyList()
        scheduler.reconcile()
        verify { workManager.cancelUniqueWork(WorkManagerPolygonDwellCheckScheduler.POLYGON_DWELL_CHECK_WORK) }
    }

    private fun visit() = GeofenceDwellVisit(
        geofenceId = POLYGON.id,
        visitId = VISIT,
        enteredAtSeconds = 1_000L,
        regionRevision = POLYGON.transitionRevision(),
        userStateGeneration = GENERATION,
        bootSessionId = BOOT,
        enteredAtElapsedMs = ENTERED_AT_MS
    )

    private companion object {
        const val USER = "user-1"
        const val BOOT = "boot"
        const val VISIT = "visit-1"
        const val GENERATION = 7L
        const val TEARDOWN = 3L
        const val ENTERED_AT_MS = 1_000_000L
        const val DUE_AT_MS = ENTERED_AT_MS + 60_000L

        val POLYGON = GeofenceRegion(
            "venue",
            0.0,
            0.0,
            100f,
            polygonVertices = listOf(
                PolygonCoordinate(-0.001, -0.001),
                PolygonCoordinate(-0.001, 0.001),
                PolygonCoordinate(0.001, 0.001),
                PolygonCoordinate(0.001, -0.001)
            ),
            dwellThresholdSeconds = 60
        )
    }
}
