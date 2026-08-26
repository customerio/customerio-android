package io.customer.geofence.worker

import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.di.pendingGeofenceDeliveryStore
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.store.PendingDeliveryStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceEventWorkerTest : RobolectricTest() {

    private val tracker: GeofenceEventTracker = mockk(relaxed = true)

    private val store get() = SDKComponent.android().pendingGeofenceDeliveryStore

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
                diGraph {
                    android { overrideDependency<GeofenceEventTracker>(tracker) }
                }
            }
        )
        store.removeAll()
    }

    // inputData carries only the store key; the worker loads the full row from the pending store, so
    // seed the store with the matching entry to model "this transition is still pending".
    private fun seed(
        geofenceId: String,
        transition: Event.GeofenceTransition,
        timestamp: Long = 0L,
        userId: String? = "user-42",
        transitionId: String = "tid-seed",
        geofenceName: String? = null,
        geosetId: String? = null,
        metadata: Map<String, JsonElement> = emptyMap()
    ): PendingGeofenceDelivery =
        PendingGeofenceDelivery(geofenceId, transition, timestamp, userId, transitionId, geofenceName, geosetId, metadata)
            .also { store.append(it) }

    @Test
    fun doWork_givenPendingEntry_expectSuccessTrackerCalledAndEntryRemoved() = runTest {
        val entry = seed("biz-1", Event.GeofenceTransition.ENTER, 99L)
        coEvery { tracker.trackEvent(any()) } returns Result.success(Unit)

        val result = createWorker(inputDataFor(entry.key)).doWork()

        result shouldBeEqualTo ListenableWorker.Result.success()
        coVerify(exactly = 1) { tracker.trackEvent(entry) }
        store.loadAll().isEmpty().shouldBeTrue()
    }

    @Test
    fun doWork_givenStoredEntryWithSnapshot_expectDeliveredFromStoreSnapshot() = runTest {
        // The worker delivers the persisted row verbatim — name, geoset, and metadata come from the
        // store snapshot, never from inputData — so a large metadata map is never at risk of loss.
        val entry = seed(
            "biz-1",
            Event.GeofenceTransition.ENTER,
            99L,
            geofenceName = "Coffee Shop",
            geosetId = "7",
            metadata = mapOf("category" to JsonPrimitive("office"), "priority" to JsonPrimitive(3))
        )
        coEvery { tracker.trackEvent(any()) } returns Result.success(Unit)

        val result = createWorker(inputDataFor(entry.key)).doWork()

        result shouldBeEqualTo ListenableWorker.Result.success()
        coVerify(exactly = 1) { tracker.trackEvent(entry) }
        store.loadAll().isEmpty().shouldBeTrue()
    }

    @Test
    fun doWork_givenExitEntry_expectExitTransitionPassed() = runTest {
        val entry = seed("biz-2", Event.GeofenceTransition.EXIT, timestamp = 0L)
        coEvery { tracker.trackEvent(any()) } returns Result.success(Unit)

        createWorker(inputDataFor(entry.key)).doWork()

        coVerify(exactly = 1) { tracker.trackEvent(entry) }
    }

    @Test
    fun doWork_givenEntryAlreadyDelivered_expectSuccessWithoutTracking() = runTest {
        // No matching entry in the store (the foreground flush already delivered + removed it):
        // the worker sees it's gone, so it must not send a duplicate.
        val result = createWorker(inputDataFor("biz-already-delivered_ENTER_tid-missing_none")).doWork()

        result shouldBeEqualTo ListenableWorker.Result.success()
        coVerify(exactly = 0) { tracker.trackEvent(any()) }
    }

    @Test
    fun pendingStore_givenDistinctSameSecondCrossings_expectBothSurvive() {
        val first = PendingGeofenceDelivery(
            "biz",
            Event.GeofenceTransition.ENTER,
            42L,
            "user-42",
            transitionId = "tid-first"
        )
        val second = first.copy(transitionId = "tid-second")

        store.appendAll(listOf(first, second))

        store.loadAll() shouldBeEqualTo listOf(first, second)
    }

    @Test
    fun doWork_givenEmptyOutbox_expectSuccessWithoutTracking() = runTest {
        val result = createWorker(Data.EMPTY).doWork()

        result shouldBeEqualTo ListenableWorker.Result.success()
        coVerify(exactly = 0) { tracker.trackEvent(any()) }
    }

    @Test
    fun doWork_givenIOException_expectRetryAndEntryRestored() = runTest {
        val entry = seed("biz", Event.GeofenceTransition.ENTER, timestamp = 0L)
        coEvery { tracker.trackEvent(any()) } returns
            Result.failure(IOException("network down"))

        val result = createWorker(inputDataFor(entry.key)).doWork()

        result shouldBeEqualTo ListenableWorker.Result.retry()
        // Left in place so a WorkManager retry — or the foreground flush — can deliver later.
        store.loadAll().map { it.key } shouldBeEqualTo listOf("biz_ENTER_tid-seed_none")
    }

    @Test
    fun doWork_givenNonIOException_expectRetryScheduledAndChainPreserved() = runTest {
        // Not Result.failure(): that cancels the dependents and discards everything queued behind
        // this row. Not Result.success() either: this node can be the last in the chain, and then
        // nothing would ever come back for the head, stranding it and every later transition.
        val entry = seed("biz", Event.GeofenceTransition.ENTER, timestamp = 0L)
        coEvery { tracker.trackEvent(any()) } returns
            Result.failure(IllegalStateException("bad state"))

        val result = createWorker(inputDataFor(entry.key)).doWork()

        result shouldBeEqualTo ListenableWorker.Result.retry()
        store.loadAll().map { it.key } shouldBeEqualTo listOf("biz_ENTER_tid-seed_none")
    }

    @Test
    fun doWork_givenTerminalFailureThenRecovery_expectHeadAndLaterTransitionsBothDelivered() = runTest {
        // The retry a terminal failure schedules must be able to drain the whole queue, not just the
        // row that failed.
        val enter = seed("biz", Event.GeofenceTransition.ENTER, timestamp = 1L, transitionId = "tid-enter")
        val exit = seed("biz", Event.GeofenceTransition.EXIT, timestamp = 2L, transitionId = "tid-exit")
        val attempts = mutableListOf<PendingGeofenceDelivery>()
        coEvery { tracker.trackEvent(any()) } coAnswers {
            firstArg<PendingGeofenceDelivery>().also(attempts::add)
            if (attempts.size == 1) {
                Result.failure(IllegalStateException("terminal for now"))
            } else {
                Result.success(Unit)
            }
        }

        createWorker(Data.EMPTY).doWork() shouldBeEqualTo ListenableWorker.Result.retry()
        createWorker(Data.EMPTY).doWork() shouldBeEqualTo ListenableWorker.Result.success()

        attempts shouldBeEqualTo listOf(enter, enter, exit)
        store.loadAll().isEmpty().shouldBeTrue()
    }

    @Test
    fun doWork_givenOlderFailure_expectNewerEntryCannotOvertakeIt() = runTest {
        val enter = seed(
            "biz",
            Event.GeofenceTransition.ENTER,
            timestamp = 1L,
            transitionId = "tid-enter"
        )
        val exit = seed(
            "biz",
            Event.GeofenceTransition.EXIT,
            timestamp = 2L,
            transitionId = "tid-exit"
        )
        val attempts = mutableListOf<PendingGeofenceDelivery>()
        coEvery { tracker.trackEvent(any()) } coAnswers {
            val entry = firstArg<PendingGeofenceDelivery>().also(attempts::add)
            if (attempts.size == 1) {
                Result.failure(IllegalStateException("temporary bad state"))
            } else {
                Result.success(Unit)
            }
        }

        createWorker(Data.EMPTY).doWork() shouldBeEqualTo ListenableWorker.Result.retry()
        createWorker(Data.EMPTY).doWork() shouldBeEqualTo ListenableWorker.Result.success()

        attempts shouldBeEqualTo listOf(enter, enter, exit)
        store.loadAll().isEmpty().shouldBeTrue()
    }

    @Test
    fun doWork_givenNullUserId_expectDroppedWithoutTrackingSoQueueDrains() = runTest {
        // Defensive-only: the receiver drops anonymous transitions before persisting, so a null-userId
        // row shouldn't exist. If one does it is also undeliverable forever, because the userId was
        // snapshotted at queue time and no retry can supply one. Leaving it at the head of an ordered
        // queue would block every later transition, so it is dropped instead.
        val anonymous = seed("biz-anon", Event.GeofenceTransition.ENTER, timestamp = 0L, userId = null)
        val deliverable = seed(
            "biz-next",
            Event.GeofenceTransition.ENTER,
            timestamp = 1L,
            transitionId = "tid-next"
        )
        coEvery { tracker.trackEvent(any()) } returns Result.success(Unit)

        val result = createWorker(inputDataFor(anonymous.key)).doWork()

        result shouldBeEqualTo ListenableWorker.Result.success()
        coVerify(exactly = 1) { tracker.trackEvent(deliverable) }
        store.loadAll().isEmpty().shouldBeTrue()
    }

    @Test
    fun doWork_givenSuccessfulSendWhoseRemovalNeverPersists_expectOneSendThenBackedOffRetry() = runTest {
        // The worker drains "the oldest row" in a loop, so a delivered row that stays on disk is read
        // again on the next iteration. Without distinguishing "sent" from "sent and recorded" that
        // loop resends the same transition as fast as the network allows, forever.
        val entry = seed("biz-stuck", Event.GeofenceTransition.ENTER, timestamp = 7L)
        coEvery { tracker.trackEvent(any()) } returns Result.success(Unit)
        val removalNeverPersists = spyk(store) { every { remove(any()) } returns false }
        SDKComponent.android()
            .overrideDependency<PendingDeliveryStore<PendingGeofenceDelivery>>(removalNeverPersists)

        val result = createWorker(inputDataFor(entry.key)).doWork()

        result shouldBeEqualTo ListenableWorker.Result.retry()
        coVerify(exactly = 1) { tracker.trackEvent(entry) }
        removalNeverPersists.loadAll().map { it.key } shouldBeEqualTo listOf("biz-stuck_ENTER_tid-seed_none")
    }

    private fun inputDataFor(key: String): Data =
        Data.Builder().putString("entry_key", key).build()

    private fun createWorker(inputData: Data): GeofenceEventWorker {
        return TestListenableWorkerBuilder<GeofenceEventWorker>(applicationMock)
            .setInputData(inputData)
            .build()
    }
}
