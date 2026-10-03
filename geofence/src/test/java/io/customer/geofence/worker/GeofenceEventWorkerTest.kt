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
        // Match key plus field: logEventDeliveryRetryable also emits ev=delivery.failed.
        val matches = capturing.messages.filter { "ev=delivery.failed" in it && "retry=" in it }
        matches.size shouldBeEqualTo 1
        return matches.first()
    }

    // The worker ignores inputData and drains the oldest row of the pending store, so tests seed it.
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

        result shouldBeEqualTo ListenableWorker.Result.retry()
        coVerify(exactly = 0) { tracker.trackEvent(any()) }
        emptyQueueRecords().shouldBeEmpty()
        unreadableRecords().size shouldBeEqualTo 1
        unreadableRecords().single() shouldContain "why=read_failed"
        unreadableRecords().single() shouldContain "retry=true"
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
            .setRunAttemptCount(GeofenceConstants.MAX_WORKER_RUN_ATTEMPTS)
            .build()

        worker.doWork() shouldBeEqualTo ListenableWorker.Result.failure()
        unreadableRecords().single() shouldContain "retry=false"
        store.loadAll().map { it.geofenceId } shouldBeEqualTo listOf("biz-1")
    }

    @Test
    fun doWork_givenAttemptsAlreadySpentOnDeliveryRetries_expectFirstUnreadableReadToGiveUp() = runTest {
        // The cap is WorkManager's run count for the whole request, not a per-cause counter.
        seed("biz-1", Event.GeofenceTransition.ENTER, 99L)
        val unreadable = spyk(store) { every { loadAllOrNull() } returns null }
        SDKComponent.android()
            .overrideDependency<PendingDeliveryStore<PendingGeofenceDelivery>>(unreadable)

        val worker = TestListenableWorkerBuilder<GeofenceEventWorker>(applicationMock)
            .setInputData(Data.EMPTY)
            .setRunAttemptCount(GeofenceConstants.MAX_WORKER_RUN_ATTEMPTS + 3)
            .build()

        worker.doWork() shouldBeEqualTo ListenableWorker.Result.failure()
        unreadableRecords().single() shouldContain "n=${GeofenceConstants.MAX_WORKER_RUN_ATTEMPTS + 3}"
        unreadableRecords().single() shouldContain "retry=false"
        coVerify(exactly = 0) { tracker.trackEvent(any()) }
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
        seed("biz-1", Event.GeofenceTransition.ENTER)
        seed("biz-2", Event.GeofenceTransition.EXIT)
        coEvery { tracker.trackEvent(any()) } returns Result.success(Unit)

        createWorker(Data.EMPTY).doWork() shouldBeEqualTo ListenableWorker.Result.success()

        store.loadAll().isEmpty().shouldBeTrue()
        emptyQueueRecords().shouldBeEmpty()
    }

    @Test
    fun doWork_givenSiblingNodeAlreadyDrainedTheChain_expectOneEmptyQueueRecord() = runTest {
        // A geoset fan-out enqueues a node per row and the first drains them all.
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
        store.loadAll().map { it.key } shouldBeEqualTo listOf("biz_ENTER_tid-seed_none")
    }

    @Test
    fun doWork_givenNonIOException_expectRetryScheduledAndChainPreserved() = runTest {
        // Not failure(): that cancels every dependent queued behind this row. Not success(): if this
        // is the chain's last node, nothing would ever retry the head.
        val entry = seed("biz", Event.GeofenceTransition.ENTER, timestamp = 0L)
        coEvery { tracker.trackEvent(any()) } returns
            Result.failure(IllegalStateException("bad state"))

        val result = createWorker(inputDataFor(entry.key)).doWork()

        result shouldBeEqualTo ListenableWorker.Result.retry()
        store.loadAll().map { it.key } shouldBeEqualTo listOf("biz_ENTER_tid-seed_none")
    }

    @Test
    fun doWork_givenPermanentlyRejectedHead_expectItDroppedAndLaterTransitionsDelivered() = runTest {
        // Every non-2xx is an IOException subclass; retrying a 400 would block every later transition.
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

        attempts shouldBeEqualTo listOf(enter, exit)
        store.loadAll().isEmpty().shouldBeTrue()
        deliveryFailedTail() shouldContain "retry=false"
    }

    @Test
    fun doWork_givenRetryableHttpStatus_expectRetryAndEntryKept() = runTest {
        val entry = seed("biz", Event.GeofenceTransition.ENTER, timestamp = 0L)
        coEvery { tracker.trackEvent(any()) } returns
            Result.failure(HttpRequestFailure(503, "unavailable"))

        val result = createWorker(inputDataFor(entry.key)).doWork()

        result shouldBeEqualTo ListenableWorker.Result.retry()
        store.loadAll().map { it.key } shouldBeEqualTo listOf("biz_ENTER_tid-seed_none")
    }

    @Test
    fun doWork_givenTerminalRejectionWhoseRemovalFails_expectRowKeptAndTailSaysItWillRetry() = runTest {
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
        // No retry can supply a missing userId, so the row is dropped rather than block the queue.
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
        // The worker drains in a loop, so a sent row left on disk would otherwise resend forever.
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
