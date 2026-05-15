package io.customer.messagingpush.store

import io.customer.commontest.extensions.random
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.customer.sdk.core.util.Logger
import io.mockk.mockk
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.amshove.kluent.shouldBeEqualTo
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

        val id = store.append(deliveryId = deliveryId, token = token)

        val entries = store.loadAll()
        entries.size shouldBeEqualTo 1
        entries[0].id shouldBeEqualTo id
        entries[0].deliveryId shouldBeEqualTo deliveryId
        entries[0].token shouldBeEqualTo token
    }

    @Test
    fun append_givenMultipleEntries_expectAllPersistedInOrder() {
        val deliveryIds = List(3) { String.random }
        val tokens = List(3) { String.random }

        val ids = deliveryIds.zip(tokens).map { (deliveryId, token) ->
            store.append(deliveryId = deliveryId, token = token)
        }

        val entries = store.loadAll()
        entries.map { it.id } shouldBeEqualTo ids
        entries.map { it.deliveryId } shouldBeEqualTo deliveryIds
        entries.map { it.token } shouldBeEqualTo tokens
    }

    @Test
    fun append_givenGeneratedIds_expectUnique() {
        val ids = List(50) { store.append(deliveryId = String.random, token = String.random) }
        ids.toSet().size shouldBeEqualTo ids.size
    }

    @Test
    fun remove_givenExistingId_expectEntryRemoved() {
        val keepId = store.append(deliveryId = String.random, token = String.random)
        val removeId = store.append(deliveryId = String.random, token = String.random)

        store.remove(removeId)

        val entries = store.loadAll()
        entries.size shouldBeEqualTo 1
        entries[0].id shouldBeEqualTo keepId
    }

    @Test
    fun remove_givenUnknownId_expectNoOp() {
        val keepId = store.append(deliveryId = String.random, token = String.random)

        store.remove("not-a-real-id")

        val entries = store.loadAll()
        entries.size shouldBeEqualTo 1
        entries[0].id shouldBeEqualTo keepId
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
    fun loadAll_givenFreshStore_expectEmpty() {
        store.loadAll().isEmpty().shouldBeTrue()
    }

    @Test
    fun append_givenOverCapacity_expectOldestDropped() {
        val firstId = store.append(deliveryId = "first", token = String.random)
        val secondId = store.append(deliveryId = "second", token = String.random)
        for (i in 0 until (PendingPushDeliveryStore.MAX_ENTRIES - 2)) {
            store.append(deliveryId = "fill-$i", token = String.random)
        }

        // Store is now exactly at MAX_ENTRIES. Both anchors should still exist.
        val beforeOverflow = store.loadAll()
        beforeOverflow.size shouldBeEqualTo PendingPushDeliveryStore.MAX_ENTRIES
        beforeOverflow.first().id shouldBeEqualTo firstId

        val lastId = store.append(deliveryId = "last", token = String.random)

        val afterOverflow = store.loadAll()
        afterOverflow.size shouldBeEqualTo PendingPushDeliveryStore.MAX_ENTRIES
        // Oldest must have been dropped.
        afterOverflow.none { it.id == firstId }.shouldBeTrue()
        // Second oldest is now the head.
        afterOverflow.first().id shouldBeEqualTo secondId
        // Newest entry is at the tail.
        afterOverflow.last().id shouldBeEqualTo lastId
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
        // Sanity-check that ids remain unique even across threads.
        entries.map { it.id }.toSet().size shouldBeEqualTo entries.size
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
    }
}
