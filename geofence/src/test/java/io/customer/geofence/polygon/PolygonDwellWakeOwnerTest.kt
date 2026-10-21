package io.customer.geofence.polygon

import android.content.Context
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.GeofenceRegistrar
import io.customer.geofence.GeofenceRegistrationGate
import io.customer.geofence.store.GeofenceDwellVisit
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.transitionRevision
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The owner against real record prefs. [os] models what each polygon's GMS registration carries, so
 * assertions read the state a run leaves the OS in, not call counts. Store reads are stubbed per
 * field and stay pending through teardown, as `stopAll` leaves entered ids and visits.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PolygonDwellWakeOwnerTest : RobolectricTest() {

    private val store: GeofenceRegionStore = mockk(relaxed = true)
    private val registrar: GeofenceRegistrar = mockk()
    private val secureUserStore: SecureUserStore = mockk()
    private val bootSessionProvider: PolygonBootSessionProvider = mockk()
    private val gate = GeofenceRegistrationGate()
    private lateinit var records: PolygonDwellWakeRecordStore

    private val os = mutableMapOf<String, String>()
    private val heldArms = mutableMapOf<String, CompletableDeferred<Unit>>()
    private var disarmFails = false

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { argument(ApplicationArgument(applicationMock)) })
        val prefs = applicationMock.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        records = PolygonDwellWakeRecordStore(prefs)

        every { secureUserStore.getUserId() } returns USER_ID
        every { bootSessionProvider.currentSessionId() } returns BOOT
        every { store.activeUserSessionId() } returns USER_ID
        every { store.userStateGeneration() } returns GENERATION
        every { store.getRegisteredIds() } returns POLYGON_IDS
        every { store.getRoutableRegisteredIds() } returns POLYGON_IDS
        every { store.getEnteredIds() } returns POLYGON_IDS
        POLYGON_IDS.forEach { id ->
            val region = polygon(id)
            every { store.getCachedRegion(id) } returns region
            every { store.getDwellVisit(id) } returns pendingVisit(region)
        }
        coEvery { registrar.armPolygonDwellWake(any(), any()) } coAnswers {
            val id = firstArg<GeofenceRegion>().id
            heldArms[id]?.await()
            os[id] = "dwell:${secondArg<Long>()}"
            Result.success(Unit)
        }
        coEvery { registrar.disarmPolygonDwellWake(any()) } coAnswers {
            if (disarmFails) {
                Result.failure(TimeoutException("GMS gave no callback"))
            } else {
                os[firstArg<GeofenceRegion>().id] = BASELINE
                Result.success(Unit)
            }
        }
    }

    @Test
    fun onTeardown_givenAnArmedWakeAndAnArmInFlight_expectBothBackToBaseline() = runTest {
        val owner = owner()
        owner.onVisitPending(FIRST)!!.join()
        os[FIRST] shouldBeEqualTo DUE_WAKE
        val held = CompletableDeferred<Unit>().also { heldArms[SECOND] = it }
        val inFlight = owner.onVisitPending(SECOND)!!
        runCurrent()

        // Waits for the gate the in-flight arm holds; both visits still read as pending.
        val teardown = owner.onTeardown()
        runCurrent()
        held.complete(Unit)
        joinAll(inFlight, teardown)

        os shouldBeEqualTo mapOf(FIRST to BASELINE, SECOND to BASELINE)
        knownRecordIds() shouldBeEqualTo emptySet<String>()
    }

    @Test
    fun restoreStaleHoldingGate_givenAFailedRestore_expectBaselineNeededAndRecordKept() = runTest {
        val owner = owner()
        owner.onVisitPending(FIRST)!!.join()
        owner.onVisitPending(SECOND)!!.join()
        // FIRST's visit ended, so its wake is stale, and GMS gives the in-place restore no answer.
        every { store.getDwellVisit(FIRST) } returns null
        disarmFails = true

        val outcome = gate.withRegistration { owner.restoreStaleHoldingGate() }

        // Sync must re-add FIRST rather than skip it as unchanged; SECOND's wake is still wanted.
        outcome shouldBeEqualTo PolygonDwellWakeRestore.Known(needingBaseline = setOf(FIRST))
        os shouldBeEqualTo mapOf(FIRST to DUE_WAKE, SECOND to DUE_WAKE)
        knownRecordIds() shouldBeEqualTo POLYGON_IDS
    }

    @Test
    fun onOsRegistrationsLost_givenAWakeArmedSinceTheLoss_expectItsRecordKept() = runTest {
        val owner = owner()
        owner.onVisitPending(FIRST)!!.join()
        // GMS dropped every geofence, the armed wake included; the cleanup queues for the gate.
        os.clear()
        val cleanup = owner.onOsRegistrationsLost()
        // A registration takes the gate first: it re-adds every polygon and confirms the baseline.
        gate.withRegistration {
            POLYGON_IDS.forEach { os[it] = BASELINE }
            owner.forgetHoldingGate(null) shouldBeEqualTo true
        }
        // SECOND's visit then arms under the loss's epoch: a wake GMS really holds.
        owner.arm(SECOND, expectedEpoch = LOSS_EPOCH) shouldBeEqualTo PolygonDwellWakeArm.ARMED
        // The race's premise: that arm took the gate before the queued cleanup ran.
        cleanup.isCompleted shouldBeEqualTo false
        cleanup.join()

        knownRecordIds() shouldBeEqualTo setOf(SECOND)
        os shouldBeEqualTo mapOf(FIRST to BASELINE, SECOND to DUE_WAKE)
    }

    @Test
    fun onOsRegistrationsLost_givenAWakeArmedBeforeTheLoss_expectRecordForgottenWithNoGmsCall() = runTest {
        val owner = owner()
        owner.onVisitPending(FIRST)!!.join()
        os.clear()

        owner.onOsRegistrationsLost().join()

        knownRecordIds() shouldBeEqualTo emptySet<String>()
        // No disarm, which would write BASELINE; a removal is unstubbed and would fail the run.
        os shouldBeEqualTo emptyMap<String, String>()
    }

    @Test
    fun restoreStaleHoldingGate_givenOnlyTheIncomingThresholdChanged_expectOldWakeRestoredBeforePublication() = runTest {
        val owner = owner()
        owner.onVisitPending(FIRST)!!.join()
        val incoming = POLYGON_IDS.associateWith(::polygon).toMutableMap()
        incoming[FIRST] = polygon(FIRST).copy(dwellThresholdSeconds = 60)

        val result = gate.withRegistration { owner.restoreStaleHoldingGate(incoming = incoming) }

        result shouldBeEqualTo PolygonDwellWakeRestore.Known(emptySet())
        os shouldBeEqualTo mapOf(FIRST to BASELINE)
        knownRecordIds() shouldBeEqualTo emptySet<String>()
        // The old cache still describes the stored visit while registration decides what to replace.
        store.getCachedRegion(FIRST)?.dwellThresholdSeconds shouldBeEqualTo 300
    }

    @Test
    fun onVisitPending_givenAnotherOwnerForTheSameVisit_expectOriginalNativeTimerKept() = runTest {
        owner().onVisitPending(FIRST)!!.join()
        val reopened = PolygonDwellWakeOwner(
            store = store, registrar = registrar, records = records, gate = gate,
            secureUserStore = secureUserStore, bootSessionProvider = bootSessionProvider,
            enabled = { true }, elapsedRealtimeMs = { NOW_MS + 50_000L }, scope = this,
            ioContext = StandardTestDispatcher(testScheduler)
        )

        reopened.onVisitPending(FIRST)!!.join()

        // A second add at the later clock would change the delay to 210 s and restart GMS's timer.
        os shouldBeEqualTo mapOf(FIRST to DUE_WAKE)
        knownRecordIds() shouldBeEqualTo setOf(FIRST)
    }

    @Test
    fun arm_givenUnreadableRecords_expectNoGmsWriteAndARequiredRestorePass() = runTest {
        applicationMock.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PolygonDwellWakeRecordStore.KEY, "{").commit()
        val owner = owner()

        owner.arm(FIRST, expectedEpoch = 0L) shouldBeEqualTo PolygonDwellWakeArm.RECORDS_UNKNOWN

        owner.needsRestorePass() shouldBeEqualTo true
        records.read() shouldBeEqualTo PolygonDwellWakeRecords.Unknown
        os shouldBeEqualTo emptyMap<String, String>()
    }

    @Test
    fun onOsRegistrationsLost_givenAnArmAnsweredAfterTheLossAndAFailedRemoval_expectRecordKept() = runTest {
        val owner = owner()
        val held = CompletableDeferred<Unit>().also { heldArms[FIRST] = it }
        val inFlight = owner.onVisitPending(FIRST)!!
        runCurrent()
        // GMS dropped every geofence while the add was outstanding, and the store forgot the live ids.
        os.clear()
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { registrar.removeGeofencesByIds(any()) } returns Result.failure(TimeoutException("no callback"))
        val cleanup = owner.onOsRegistrationsLost()
        runCurrent()

        // The add lands after the loss, and the superseded arm's removal gets no answer.
        held.complete(Unit)
        joinAll(inFlight, cleanup)

        // GMS may hold this wake, so only a confirmed restore or baseline may drop its record.
        os shouldBeEqualTo mapOf(FIRST to DUE_WAKE)
        knownRecordIds() shouldBeEqualTo setOf(FIRST)
    }

    @Test
    fun admitNativeWake_givenPreFenceRecordAfterRelaunch_expectRefusedUntilConfirmedCleanupAndFreshArmAllowed() = runTest {
        owner().onVisitPending(FIRST)!!.join()
        val reopened = owner()
        // A cold process can adopt durable wake ownership without resetting GMS's clock.
        reopened.admitNativeWake(FIRST) shouldBeEqualTo PolygonDwellWakeAdmission.Admitted("visit-$FIRST")
        reopened.fence()
        reopened.admitNativeWake(FIRST) shouldBeEqualTo PolygonDwellWakeAdmission.Stale
        reopened.needsRestorePass() shouldBeEqualTo true
        knownRecordIds() shouldBeEqualTo setOf(FIRST)
        disarmFails = true
        val outcome = gate.withRegistration { reopened.restoreStaleHoldingGate() }
        outcome shouldBeEqualTo PolygonDwellWakeRestore.Known(setOf(FIRST))
        knownRecordIds() shouldBeEqualTo setOf(FIRST)
        // A separate current visit can arm after the fence; refusing old records must not refuse it.
        reopened.onVisitPending(SECOND)!!.join()
        reopened.admitNativeWake(SECOND) shouldBeEqualTo PolygonDwellWakeAdmission.Admitted("visit-$SECOND")
    }

    private fun TestScope.owner() = PolygonDwellWakeOwner(
        store = store,
        registrar = registrar,
        records = records,
        gate = gate,
        secureUserStore = secureUserStore,
        bootSessionProvider = bootSessionProvider,
        enabled = { true },
        elapsedRealtimeMs = { NOW_MS },
        scope = this,
        ioContext = StandardTestDispatcher(testScheduler)
    )

    private fun knownRecordIds(): Set<String> =
        (records.read() as PolygonDwellWakeRecords.Known).byPolygonId.keys

    private fun polygon(id: String) = GeofenceRegion(
        id = id,
        latitude = 37.775,
        longitude = -122.4194,
        radius = 120f,
        polygonVertices = VERTICES,
        dwellThresholdSeconds = 300
    )

    private fun pendingVisit(region: GeofenceRegion) = GeofenceDwellVisit(
        geofenceId = region.id,
        visitId = "visit-${region.id}",
        enteredAtSeconds = 0L,
        regionRevision = region.transitionRevision(),
        userStateGeneration = GENERATION,
        bootSessionId = BOOT,
        enteredAtElapsedMs = 0L
    )

    private companion object {
        const val USER_ID = "user"
        const val BOOT = "boot-1"
        const val GENERATION = 7L
        const val NOW_MS = 100_000L

        /** The owner starts at epoch 0, and an OS loss's fence is its first. */
        const val LOSS_EPOCH = 1L
        const val FIRST = "first"
        const val SECOND = "second"
        const val PREFS = "cio_test_polygon_dwell_wake_owner"
        const val BASELINE = "baseline"

        /** Entered at 0, now 100 s in: 300 s threshold less 100 s, plus the 60 s default grace. */
        const val DUE_WAKE = "dwell:260000"
        val POLYGON_IDS = setOf(FIRST, SECOND)
        val VERTICES = listOf(
            PolygonCoordinate(37.7745, -122.4200),
            PolygonCoordinate(37.7745, -122.4188),
            PolygonCoordinate(37.7755, -122.4188),
            PolygonCoordinate(37.7755, -122.4200)
        )
    }
}
