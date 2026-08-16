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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
    fun doWork_givenPreUpgradeLegacyKey_expectFindsAndDeliversPersistedEntry() = runTest {
        val entry = seed("biz-legacy", Event.GeofenceTransition.ENTER, timestamp = 42L)
        coEvery { tracker.trackEvent(any()) } returns Result.success(Unit)

        val result = createWorker(inputDataFor(entry.legacyKey)).doWork()

        result shouldBeEqualTo ListenableWorker.Result.success()
        coVerify(exactly = 1) { tracker.trackEvent(entry) }
        store.loadAll().isEmpty().shouldBeTrue()
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
    fun doWork_givenMissingEntryKey_expectFailureWithoutTracking() = runTest {
        val result = createWorker(Data.EMPTY).doWork()

        result shouldBeEqualTo ListenableWorker.Result.failure()
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
    fun doWork_givenNonIOException_expectFailureAndEntryRestored() = runTest {
        val entry = seed("biz", Event.GeofenceTransition.ENTER, timestamp = 0L)
        coEvery { tracker.trackEvent(any()) } returns
            Result.failure(IllegalStateException("bad state"))

        val result = createWorker(inputDataFor(entry.key)).doWork()

        result shouldBeEqualTo ListenableWorker.Result.failure()
        store.loadAll().map { it.key } shouldBeEqualTo listOf("biz_ENTER_tid-seed_none")
    }

    @Test
    fun doWork_givenNullUserId_expectDeferredWithoutTracking() = runTest {
        // Defensive-only: the receiver drops anonymous transitions before persisting, so a
        // null-userId row shouldn't exist. If one does, leave it rather than send a track
        // the backend would reject.
        val entry = seed("biz-anon", Event.GeofenceTransition.ENTER, timestamp = 0L, userId = null)

        val result = createWorker(inputDataFor(entry.key)).doWork()

        result shouldBeEqualTo ListenableWorker.Result.success()
        coVerify(exactly = 0) { tracker.trackEvent(any()) }
        // Entry must NOT be removed — flush still needs it.
        store.loadAll().map { it.key } shouldBeEqualTo listOf("biz-anon_ENTER_tid-seed_none")
    }

    private fun inputDataFor(key: String): Data =
        Data.Builder().putString("entry_key", key).build()

    private fun createWorker(inputData: Data): GeofenceEventWorker {
        return TestListenableWorkerBuilder<GeofenceEventWorker>(applicationMock)
            .setInputData(inputData)
            .build()
    }
}
