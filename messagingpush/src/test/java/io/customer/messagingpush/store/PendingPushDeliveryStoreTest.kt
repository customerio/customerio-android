package io.customer.messagingpush.store

import io.customer.commontest.extensions.random
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.customer.sdk.core.util.Logger
import io.mockk.mockk
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterOrEqualTo
import org.amshove.kluent.shouldBeLessOrEqualTo
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PendingPushDeliveryStoreTest : IntegrationTest() {

    private val mockLogger: Logger = mockk(relaxed = true)
    private lateinit var store: PendingPushDeliveryStore

    override fun setup(testConfig: io.customer.commontest.config.TestConfig) {
        super.setup(testConfig)
        store = PendingPushDeliveryStore(context = applicationMock, logger = mockLogger)
        store.removeAll()
    }

    @Test
    fun append_givenSingleEntry_expectLoadAllReturnsIt() {
        val deliveryId = String.random
        val token = String.random
        val before = System.currentTimeMillis()

        store.append(deliveryId = deliveryId, token = token)

        val after = System.currentTimeMillis()
        val entries = store.loadAll()
        entries.size shouldBeEqualTo 1
        entries[0].deliveryId shouldBeEqualTo deliveryId
        entries[0].token shouldBeEqualTo token
        entries[0].timestamp shouldBeGreaterOrEqualTo before
        entries[0].timestamp shouldBeLessOrEqualTo after
    }

    @Test
    fun append_givenMultipleEntries_expectAllPersistedInOrder() {
        val deliveryIds = List(3) { String.random }
        val tokens = List(3) { String.random }

        deliveryIds.zip(tokens).forEach { (deliveryId, token) ->
            store.append(deliveryId = deliveryId, token = token)
        }

        val entries = store.loadAll()
        entries.map { it.deliveryId } shouldBeEqualTo deliveryIds
        entries.map { it.token } shouldBeEqualTo tokens
    }

    @Test
    fun remove_givenExistingDeliveryId_expectEntryRemoved() {
        val keepDeliveryId = String.random
        val removeDeliveryId = String.random
        store.append(deliveryId = keepDeliveryId, token = String.random)
        store.append(deliveryId = removeDeliveryId, token = String.random)

        store.remove(removeDeliveryId)

        val entries = store.loadAll()
        entries.size shouldBeEqualTo 1
        entries[0].deliveryId shouldBeEqualTo keepDeliveryId
    }

    @Test
    fun remove_givenUnknownDeliveryId_expectNoOp() {
        val keepDeliveryId = String.random
        store.append(deliveryId = keepDeliveryId, token = String.random)

        store.remove("not-a-real-delivery-id")

        val entries = store.loadAll()
        entries.size shouldBeEqualTo 1
        entries[0].deliveryId shouldBeEqualTo keepDeliveryId
    }

    @Test
    fun removeAll_givenPopulatedStore_expectEmptyAfterCall() {
        repeat(5) {
            store.append(deliveryId = String.random, token = String.random)
        }

        store.removeAll()

        store.loadAll().isEmpty().shouldBeTrue()
    }

    @Test
    fun removeAllIds_givenSubsetOfEntries_expectOnlyMatchingRemoved() {
        val keep = "keep-${String.random}"
        val remove1 = "remove1-${String.random}"
        val remove2 = "remove2-${String.random}"
        store.append(deliveryId = remove1, token = String.random)
        store.append(deliveryId = keep, token = String.random)
        store.append(deliveryId = remove2, token = String.random)

        store.removeAll(listOf(remove1, remove2))

        val remaining = store.loadAll()
        remaining.size shouldBeEqualTo 1
        remaining[0].deliveryId shouldBeEqualTo keep
    }

    @Test
    fun removeAllIds_givenIdsNotPresent_expectStoreUnchanged() {
        val kept = listOf(String.random, String.random)
        kept.forEach { store.append(deliveryId = it, token = String.random) }

        store.removeAll(listOf("nonexistent-1", "nonexistent-2"))

        store.loadAll().map { it.deliveryId } shouldBeEqualTo kept
    }

    @Test
    fun removeAllIds_givenEmptyCollection_expectNoOp() {
        val kept = String.random
        store.append(deliveryId = kept, token = String.random)

        store.removeAll(emptyList<String>())

        val remaining = store.loadAll()
        remaining.size shouldBeEqualTo 1
        remaining[0].deliveryId shouldBeEqualTo kept
    }

    @Test
    fun removeAllIds_givenEntryAppendedAfterLoad_expectAppendedEntrySurvives() {
        val loaded = "loaded-${String.random}"
        val appendedMidFlush = "midflush-${String.random}"
        store.append(deliveryId = loaded, token = String.random)

        // Simulate the flush sequence: snapshot ids before append, then remove
        // only those ids — the entry appended afterward must survive.
        val flushedIds = store.loadAll().map { it.deliveryId }
        store.append(deliveryId = appendedMidFlush, token = String.random)
        store.removeAll(flushedIds)

        val remaining = store.loadAll()
        remaining.size shouldBeEqualTo 1
        remaining[0].deliveryId shouldBeEqualTo appendedMidFlush
    }

    @Test
    fun loadAll_givenFreshStore_expectEmpty() {
        store.loadAll().isEmpty().shouldBeTrue()
    }

    @Test
    fun append_givenOverCapacity_expectOldestDropped() {
        val firstDeliveryId = "first"
        val secondDeliveryId = "second"
        store.append(deliveryId = firstDeliveryId, token = String.random)
        store.append(deliveryId = secondDeliveryId, token = String.random)
        for (i in 0 until (PendingPushDeliveryStore.MAX_ENTRIES - 2)) {
            store.append(deliveryId = "fill-$i", token = String.random)
        }

        // Store is now exactly at MAX_ENTRIES. Both anchors should still exist.
        val beforeOverflow = store.loadAll()
        beforeOverflow.size shouldBeEqualTo PendingPushDeliveryStore.MAX_ENTRIES
        beforeOverflow.first().deliveryId shouldBeEqualTo firstDeliveryId

        val lastDeliveryId = "last"
        store.append(deliveryId = lastDeliveryId, token = String.random)

        val afterOverflow = store.loadAll()
        afterOverflow.size shouldBeEqualTo PendingPushDeliveryStore.MAX_ENTRIES
        // Oldest (smallest-timestamp) must have been dropped.
        afterOverflow.none { it.deliveryId == firstDeliveryId }.shouldBeTrue()
        // Second oldest is now the head.
        afterOverflow.first().deliveryId shouldBeEqualTo secondDeliveryId
        // Newest entry is at the tail.
        afterOverflow.last().deliveryId shouldBeEqualTo lastDeliveryId
    }

    @Test
    fun append_givenConcurrentWriters_expectNoLostEntries() {
        val threadCount = 8
        val perThread = 25
        val executor = Executors.newFixedThreadPool(threadCount)
        try {
            val tasks = List(threadCount) { threadIndex ->
                executor.submit {
                    repeat(perThread) { i ->
                        store.append(
                            deliveryId = "thread-$threadIndex-$i",
                            token = String.random
                        )
                    }
                }
            }
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val entries = store.loadAll()
        // Total writes (threadCount * perThread = 200) exceed MAX_ENTRIES (100),
        // so the store should be capped — confirming the capacity guard works
        // under concurrent access without throwing.
        entries.size shouldBeEqualTo PendingPushDeliveryStore.MAX_ENTRIES
        // Sanity-check that deliveryIds remain unique even across threads.
        entries.map { it.deliveryId }.toSet().size shouldBeEqualTo entries.size
    }

    @Test
    fun loadAll_givenCorruptOnDiskContent_expectEmptyAndDoesNotThrow() {
        // Pre-fill with valid data, then corrupt the file directly.
        store.append(deliveryId = String.random, token = String.random)
        val file = java.io.File(applicationMock.applicationContext.filesDir, "cio_pending_push_delivery.json")
        file.exists().shouldBeTrue()
        file.writeText("this is not valid json {]")

        val entries = store.loadAll()
        entries shouldNotBe null
        entries.isEmpty().shouldBeTrue()
    }

    @Test
    fun append_givenSerializedContent_expectKeysPresent() {
        val deliveryId = "delivery-${String.random}"
        val token = "token-${String.random}"

        store.append(deliveryId = deliveryId, token = token)

        val file = java.io.File(applicationMock.applicationContext.filesDir, "cio_pending_push_delivery.json")
        val raw = file.readText()
        raw shouldContain deliveryId
        raw shouldContain token
        raw shouldContain "timestamp"
    }
}
