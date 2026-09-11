package io.customer.geofence.worker

import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.GeofenceConstants
import io.customer.geofence.GeofenceDiagnostics
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.di.pendingGeofenceDeliveryStore
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.network.HttpRequestFailure
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.core.util.Logger
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
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContain
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceEventWorkerTest : RobolectricTest() {

    private val tracker: GeofenceEventTracker = mockk(relaxed = true)

    /** Captures formatted messages: the tail's value is the assertion, not a call count. */
    private class CapturingLogger : Logger {
        val messages = mutableListOf<String>()
        override var logLevel: CioLogLevel = CioLogLevel.DEBUG
        override fun setLogDispatcher(dispatcher: ((CioLogLevel, String) -> Unit)?) = Unit
        override fun info(message: String, tag: String?) = record(message)
        override fun debug(message: String, tag: String?) = record(message)
        override fun error(message: String, tag: String?, throwable: Throwable?) = record(message)
        private fun record(message: String) {
            messages.add(message)
        }
    }

    private val capturing = CapturingLogger()

    private val store get() = SDKComponent.android().pendingGeofenceDeliveryStore

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
                diGraph {
                    sdk { overrideDependency<GeofenceLogger>(GeofenceLogger(capturing)) }
                    android { overrideDependency<GeofenceEventTracker>(tracker) }
                }
            }
        )
        store.removeAll()
        GeofenceDiagnostics.setEnabledForTesting(true)
    }

    @After
    fun resetDiagnostics() {
        // null, not false: false would pin the gate off for every later test class in this JVM.
        GeofenceDiagnostics.setEnabledForTesting(null)
    }

    private fun deliveryFailedTail(): String {
        // Filtered on the machine key plus the field, not the prose: logEventDeliveryRetryable also
        // emits ev=delivery.failed, and matching on wording turns a reword into a confusing throw.
        val matches = capturing.messages.filter { "ev=delivery.failed" in it && "retry=" in it }
        matches.size shouldBeEqualTo 1
        return matches.first()
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

    private fun unreadableRecords(): List<String> =
        capturing.messages.filter { "ev=queue.unreadable" in it }

    @Test
    fun doWork_givenUnreadableQueue_expectRetryAndNotAnEmptyQueueRecord() = runTest {
        seed("biz-1", Event.GeofenceTransition.ENTER, 99L)
        val unreadable = spyk(store) { every { loadAllOrNull() } returns null }
        SDKComponent.android()
            .overrideDependency<PendingDeliveryStore<PendingGeofenceDelivery>>(unreadable)

        val result = createWorker(Data.EMPTY).doWork()

        // Retried, not reported drained: the row is still on disk and nothing was sent.
        result shouldBeEqualTo ListenableWorker.Result.retry()
        coVerify(exactly = 0) { tracker.trackEvent(any()) }
        // queue_empty names a cause never established for a file we could not read.
        emptyQueueRecords().shouldBeEmpty()
        unreadableRecords().size shouldBeEqualTo 1
        unreadableRecords().single() shouldContain "why=read_failed"
        unreadableRecords().single() shouldContain "retry=true"
        // The wake path, not the foreground flush, which files the same ev.
        unreadableRecords().single() shouldContain "via=work_manager"
    }

    @Test
    fun doWork_givenUnreadableQueueAtAttemptCap_expectGiveUpRatherThanBackoffLoop() = runTest {
        seed("biz-1", Event.GeofenceTransition.ENTER, 99L)
        val unreadable = spyk(store) { every { loadAllOrNull() } returns null }
        SDKComponent.android()
            .overrideDependency<PendingDeliveryStore<PendingGeofenceDelivery>>(unreadable)

        val worker = TestListenableWorkerBuilder<GeofenceEventWorker>(applicationMock)
            .setInputData(Data.EMPTY)
            .setRunAttemptCount(GeofenceConstants.MAX_UNREADABLE_QUEUE_ATTEMPTS)
            .build()

        // Retrying forever recovers nothing; the rows survive because nothing overwrites them.
        worker.doWork() shouldBeEqualTo ListenableWorker.Result.failure()
        unreadableRecords().single() shouldContain "retry=false"
        store.loadAll().map { it.geofenceId } shouldBeEqualTo listOf("biz-1")
    }

    private fun emptyQueueRecords(): List<String> =
        capturing.messages.filter { "ev=delivery.sent" in it && "why=queue_empty" in it }

    @Test
    fun doWork_givenEmptyQueueOnWake_expectEmptyQueueRecord() = runTest {
        createWorker(Data.EMPTY).doWork() shouldBeEqualTo ListenableWorker.Result.success()

        emptyQueueRecords().size shouldBeEqualTo 1
    }

    @Test
    fun doWork_givenQueueDrainedSuccessfully_expectNoEmptyQueueRecord() = runTest {
        // The drain exits through the same empty-queue branch, so without the guard every
        // successful delivery would also report a flush that overtook it.
        seed("biz-1", Event.GeofenceTransition.ENTER)
        seed("biz-2", Event.GeofenceTransition.EXIT)
        coEvery { tracker.trackEvent(any()) } returns Result.success(Unit)

        createWorker(Data.EMPTY).doWork() shouldBeEqualTo ListenableWorker.Result.success()

        store.loadAll().isEmpty().shouldBeTrue()
        emptyQueueRecords().shouldBeEmpty()
    }

    @Test
    fun doWork_givenSiblingNodeAlreadyDrainedTheChain_expectOneEmptyQueueRecord() = runTest {
        // A geoset fan-out enqueues one node per row and the first drains them all, so the later
        // nodes wake to an empty store. This is the common path, not the foreground flush.
        seed("biz-1", Event.GeofenceTransition.ENTER, transitionId = "tid-fanout", geosetId = "geoset-a")
        seed("biz-1", Event.GeofenceTransition.ENTER, transitionId = "tid-fanout", geosetId = "geoset-b")
        coEvery { tracker.trackEvent(any()) } returns Result.success(Unit)

        createWorker(Data.EMPTY).doWork() shouldBeEqualTo ListenableWorker.Result.success()
        createWorker(Data.EMPTY).doWork() shouldBeEqualTo ListenableWorker.Result.success()

        coVerify(exactly = 2) { tracker.trackEvent(any()) }
        emptyQueueRecords().size shouldBeEqualTo 1
        capturing.messages.none { "ev=delivery.failed" in it }.shouldBeTrue()
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
    fun doWork_givenPermanentlyRejectedHead_expectItDroppedAndLaterTransitionsDelivered() = runTest {
        // Every non-2xx reaches us as an IOException subclass, so before the status was read a 400
        // classified as retryable and this row was resent forever. One rejected payload then blocked
        // every later ENTER and EXIT, because each node drains the oldest row.
        val enter = seed("biz", Event.GeofenceTransition.ENTER, timestamp = 1L, transitionId = "tid-enter")
        val exit = seed("biz", Event.GeofenceTransition.EXIT, timestamp = 2L, transitionId = "tid-exit")
        val attempts = mutableListOf<PendingGeofenceDelivery>()
        coEvery { tracker.trackEvent(any()) } coAnswers {
            firstArg<PendingGeofenceDelivery>().also(attempts::add)
            if (attempts.size == 1) {
                Result.failure(HttpRequestFailure(400, "invalid payload"))
            } else {
                Result.success(Unit)
            }
        }

        createWorker(Data.EMPTY).doWork() shouldBeEqualTo ListenableWorker.Result.success()

        // The rejected head is dropped rather than retried, and the drain continues past it.
        attempts shouldBeEqualTo listOf(enter, exit)
        store.loadAll().isEmpty().shouldBeTrue()
        // The tail has to say what actually happened to the row, or a replay run reads a dropped
        // transition as one still queued.
        deliveryFailedTail() shouldContain "retry=false"
    }

    @Test
    fun doWork_givenRetryableHttpStatus_expectRetryAndEntryKept() = runTest {
        // The counterpart: a 503 is the server failing, not refusing, so the row must survive.
        val entry = seed("biz", Event.GeofenceTransition.ENTER, timestamp = 0L)
        coEvery { tracker.trackEvent(any()) } returns
            Result.failure(HttpRequestFailure(503, "unavailable"))

        val result = createWorker(inputDataFor(entry.key)).doWork()

        result shouldBeEqualTo ListenableWorker.Result.retry()
        store.loadAll().map { it.key } shouldBeEqualTo listOf("biz_ENTER_tid-seed_none")
    }

    @Test
    fun doWork_givenTerminalRejectionWhoseRemovalFails_expectRowKeptAndTailSaysItWillRetry() = runTest {
        // The case the retry field exists for. The payload is refused permanently, but the row that
        // proves it could not be removed, so it is still queued and the tail must not report it as
        // dropped. Reading retry=false here would send someone looking for a transition that is
        // still on disk.
        val entry = seed("biz", Event.GeofenceTransition.ENTER, timestamp = 0L)
        val failingRemoval = spyk(store) { every { remove(any()) } returns false }
        SDKComponent.android()
            .overrideDependency<PendingDeliveryStore<PendingGeofenceDelivery>>(failingRemoval)
        coEvery { tracker.trackEvent(any()) } returns
            Result.failure(HttpRequestFailure(400, "invalid payload"))

        val result = createWorker(inputDataFor(entry.key)).doWork()

        result shouldBeEqualTo ListenableWorker.Result.retry()
        failingRemoval.loadAll().map { it.key } shouldBeEqualTo listOf(entry.key)
        deliveryFailedTail() shouldContain "retry=true"
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
        // Same log method as the permanent-rejection case, opposite disposal: this row survives.
        deliveryFailedTail() shouldContain "retry=true"
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
