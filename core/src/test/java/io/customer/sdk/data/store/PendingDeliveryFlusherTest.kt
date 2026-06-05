package io.customer.sdk.data.store

import androidx.work.Operation
import androidx.work.WorkManager
import com.google.common.util.concurrent.Futures
import io.customer.commontest.core.RobolectricTest
import io.customer.commontest.util.DispatchersProviderStub
import io.customer.sdk.core.util.CustomerIOWorkManagerProvider
import io.customer.sdk.core.util.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import kotlinx.serialization.Serializable
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PendingDeliveryFlusherTest : RobolectricTest() {

    private val mockLogger: Logger = mockk(relaxed = true)

    // WorkManager unavailable: keeps the flush synchronous (no cancelUniqueWork().await())
    // and lets us assert the claim/publish/restore logic in isolation. The WorkManager
    // cancel path is integration-tested in the push and geofence modules.
    private val workManagerProvider: CustomerIOWorkManagerProvider = mockk {
        every { getWorkManager() } returns null
    }
    private val dispatchers = DispatchersProviderStub()

    @Serializable
    private data class TestEntry(val id: String) : PendingDeliveryStore.PendingDeliveryEntry {
        override val key: String get() = id
    }

    private fun newStore() = PendingDeliveryStore(
        context = contextMock,
        fileName = "cio_test_flusher.json",
        elementSerializer = TestEntry.serializer(),
        logger = mockLogger
    ).also { it.removeAll() }

    private fun newFlusher(store: PendingDeliveryStore<TestEntry>) =
        PendingDeliveryFlusher(store, workManagerProvider, dispatchers)

    private class RecordingCallbacks : PendingDeliveryFlusher.Callbacks<TestEntry>() {
        var snapshotCount: Int? = null
        var completeCount: Int? = null
        val cancelled = mutableListOf<String>()
        val published = mutableListOf<String>()
        val failed = mutableListOf<String>()

        override fun onSnapshot(count: Int) { snapshotCount = count }
        override fun onWorkCancelled(entry: TestEntry) { cancelled += entry.key }
        override fun onPublished(entry: TestEntry) { published += entry.key }
        override fun onEntryFailed(entry: TestEntry, cause: Throwable) { failed += entry.key }
        override fun onComplete(count: Int) { completeCount = count }
    }

    private fun immediateSuccessfulOperation(): Operation = mockk(relaxed = true) {
        every { result } returns Futures.immediateFuture(Operation.SUCCESS)
    }

    @Test
    fun flush_givenEmptyStore_expectSnapshotZeroAndNoPublishNoComplete() {
        val store = newStore()
        val callbacks = RecordingCallbacks()
        val publishedKeys = mutableListOf<String>()

        newFlusher(store).flush(callbacks) { publishedKeys += it.key }

        callbacks.snapshotCount shouldBeEqualTo 0
        publishedKeys shouldBeEqualTo emptyList()
        // Mirrors push: an empty store returns before onComplete.
        callbacks.completeCount shouldBeEqualTo null
    }

    @Test
    fun flush_givenEntries_expectEachClaimedPublishedAndStoreEmptied() {
        val store = newStore()
        listOf("a", "b", "c").forEach { store.append(TestEntry(it)) }
        val callbacks = RecordingCallbacks()
        val publishedKeys = mutableListOf<String>()

        newFlusher(store).flush(callbacks) { publishedKeys += it.key }

        publishedKeys shouldBeEqualTo listOf("a", "b", "c")
        callbacks.snapshotCount shouldBeEqualTo 3
        callbacks.published shouldBeEqualTo listOf("a", "b", "c")
        callbacks.completeCount shouldBeEqualTo 3
        store.loadAll().isEmpty().shouldBeTrue()
    }

    @Test
    fun flush_givenWorkManagerAvailable_expectCancelsUniqueWorkByKeyBeforePublishing() {
        val store = newStore()
        listOf("a", "b").forEach { store.append(TestEntry(it)) }
        val workManager: WorkManager = mockk(relaxed = true) {
            every { cancelUniqueWork(any()) } returns immediateSuccessfulOperation()
        }
        every { workManagerProvider.getWorkManager() } returns workManager
        val callbacks = RecordingCallbacks()
        val publishedKeys = mutableListOf<String>()

        newFlusher(store).flush(callbacks) { publishedKeys += it.key }

        // Each entry's WorkManager unique work is cancelled before it's published,
        // so the worker can't also deliver.
        verifyOrder {
            workManager.cancelUniqueWork("a")
            workManager.cancelUniqueWork("b")
        }
        callbacks.cancelled shouldBeEqualTo listOf("a", "b")
        publishedKeys shouldBeEqualTo listOf("a", "b")
        callbacks.completeCount shouldBeEqualTo 2
        store.loadAll().isEmpty().shouldBeTrue()
    }

    @Test
    fun flush_givenPublishThrowsForOneEntry_expectOthersStillProcessed() {
        val store = newStore()
        listOf("a", "bad", "c").forEach { store.append(TestEntry(it)) }
        val callbacks = RecordingCallbacks()
        val publishedKeys = mutableListOf<String>()

        newFlusher(store).flush(callbacks) { entry ->
            if (entry.key == "bad") throw IllegalStateException("publish failed")
            publishedKeys += entry.key
        }

        // "bad" was claimed before publish threw, so it is dropped (not duplicated) —
        // the deliberate "lose one rather than double-count" trade-off.
        publishedKeys shouldBeEqualTo listOf("a", "c")
        callbacks.failed shouldBeEqualTo listOf("bad")
        callbacks.completeCount shouldBeEqualTo 2
        store.loadAll().isEmpty().shouldBeTrue()
    }
}
