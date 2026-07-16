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
import io.mockk.verify
import io.mockk.verifyOrder
import java.io.File
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

    private val fileName = "cio_test_flusher.json"

    private fun newStore() = PendingDeliveryStore(
        context = contextMock,
        fileName = fileName,
        elementSerializer = TestEntry.serializer(),
        logger = mockLogger
    ).also { it.removeAll() }

    private fun storeFile(): File = File(contextMock.applicationContext.filesDir, fileName)

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

    @Test
    fun flush_givenAtMostOnceClaimWriteFails_expectNotPublishedAndRowRetainedForNextFlush() {
        val store = newStore()
        store.append(TestEntry("a"))
        val workManager: WorkManager = mockk(relaxed = true) {
            every { cancelUniqueWork(any()) } returns immediateSuccessfulOperation()
        }
        every { workManagerProvider.getWorkManager() } returns workManager
        val callbacks = RecordingCallbacks()
        val publishedKeys = mutableListOf<String>()

        // Entry stays readable but the claiming write can't land, so claim reports failure and the
        // flush backs off without publishing (no duplicate). The worker is already cancelled by then,
        // so the retained row is retried on the next foreground flush — not by the worker.
        storeFile().setReadOnly()
        try {
            newFlusher(store).flush(callbacks) { publishedKeys += it.key }
        } finally {
            storeFile().setWritable(true)
        }

        verify { workManager.cancelUniqueWork("a") }
        publishedKeys shouldBeEqualTo emptyList()
        callbacks.published shouldBeEqualTo emptyList()
        callbacks.completeCount shouldBeEqualTo 0
        store.loadAll().map { it.key } shouldBeEqualTo listOf("a")
    }

    @Test
    fun flush_givenAtLeastOnce_expectPublishedThenStoreEmptied() {
        val store = newStore()
        listOf("a", "b").forEach { store.append(TestEntry(it)) }
        val callbacks = RecordingCallbacks()
        val publishedKeys = mutableListOf<String>()

        newFlusher(store).flush(callbacks, PendingDeliveryFlusher.DeliveryGuarantee.AT_LEAST_ONCE) {
            publishedKeys += it.key
        }

        publishedKeys shouldBeEqualTo listOf("a", "b")
        callbacks.published shouldBeEqualTo listOf("a", "b")
        callbacks.completeCount shouldBeEqualTo 2
        store.loadAll().isEmpty().shouldBeTrue()
    }

    @Test
    fun flush_givenAtLeastOnceAndPublishThrows_expectEntryRetainedForRetry() {
        val store = newStore()
        listOf("a", "bad", "c").forEach { store.append(TestEntry(it)) }
        val callbacks = RecordingCallbacks()
        val publishedKeys = mutableListOf<String>()

        newFlusher(store).flush(callbacks, PendingDeliveryFlusher.DeliveryGuarantee.AT_LEAST_ONCE) { entry ->
            if (entry.key == "bad") throw IllegalStateException("publish failed")
            publishedKeys += entry.key
        }

        // Publish precedes removal, so a throw keeps "bad" for the next flush to retry;
        // successfully published entries are removed.
        publishedKeys shouldBeEqualTo listOf("a", "c")
        callbacks.failed shouldBeEqualTo listOf("bad")
        callbacks.completeCount shouldBeEqualTo 2
        store.loadAll().map { it.key } shouldBeEqualTo listOf("bad")
    }

    @Test
    fun flush_givenAtLeastOncePublishThrowsWithWorkManager_expectFailedEntryWorkerNotCancelled() {
        val store = newStore()
        listOf("bad", "ok").forEach { store.append(TestEntry(it)) }
        val workManager: WorkManager = mockk(relaxed = true) {
            every { cancelUniqueWork(any()) } returns immediateSuccessfulOperation()
        }
        every { workManagerProvider.getWorkManager() } returns workManager
        val callbacks = RecordingCallbacks()

        newFlusher(store).flush(callbacks, PendingDeliveryFlusher.DeliveryGuarantee.AT_LEAST_ONCE) { entry ->
            if (entry.key == "bad") throw IllegalStateException("publish failed")
        }

        // Cancel runs only after a successful publish, so a failed entry keeps its worker as the
        // durable fallback (and the row is retained); a delivered entry has its worker cancelled.
        verify(exactly = 0) { workManager.cancelUniqueWork("bad") }
        verify { workManager.cancelUniqueWork("ok") }
        callbacks.failed shouldBeEqualTo listOf("bad")
        store.loadAll().map { it.key } shouldBeEqualTo listOf("bad")
    }
}
