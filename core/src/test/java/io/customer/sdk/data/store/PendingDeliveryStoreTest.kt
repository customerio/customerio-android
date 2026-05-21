package io.customer.sdk.data.store

import io.customer.commontest.core.RobolectricTest
import io.customer.sdk.core.util.Logger
import io.mockk.mockk
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotBe
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PendingDeliveryStoreTest : RobolectricTest() {

    private val mockLogger: Logger = mockk(relaxed = true)
    private val fileName = "cio_test_pending_delivery.json"

    private data class TestEntry(val id: String, val payload: String, val ts: Long)

    private val testSerializer = object : PendingDeliveryStore.Serializer<TestEntry> {
        override fun key(entry: TestEntry): String = entry.id
        override fun timestamp(entry: TestEntry): Long = entry.ts
        override fun toJson(entry: TestEntry): JSONObject = JSONObject().apply {
            put("id", entry.id)
            put("payload", entry.payload)
            put("ts", entry.ts)
        }

        override fun fromJson(obj: JSONObject): TestEntry? {
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
            val payload = obj.optString("payload").takeIf { it.isNotBlank() } ?: return null
            if (!obj.has("ts")) return null
            return TestEntry(id = id, payload = payload, ts = obj.optLong("ts"))
        }
    }

    private val timestamp = AtomicLong(1)
    private fun entry(id: String, payload: String = "p-$id"): TestEntry =
        TestEntry(id = id, payload = payload, ts = timestamp.incrementAndGet())

    private fun newStore(maxEntries: Int = PendingDeliveryStore.DEFAULT_MAX_ENTRIES) =
        PendingDeliveryStore(
            context = contextMock,
            fileName = fileName,
            serializer = testSerializer,
            logger = mockLogger,
            maxEntries = maxEntries
        ).also { it.removeAll() }

    private fun storeFile(): File = File(contextMock.applicationContext.filesDir, fileName)

    @Test
    fun append_givenSingleEntry_expectLoadAllReturnsIt() {
        val store = newStore()
        val e = entry(id = "id1")

        store.append(e)

        val entries = store.loadAll()
        entries.size shouldBeEqualTo 1
        entries[0] shouldBeEqualTo e
    }

    @Test
    fun append_givenMultipleEntries_expectAllPersistedInInsertionOrder() {
        val store = newStore()
        val ids = listOf("a", "b", "c")
        ids.forEach { store.append(entry(it)) }

        store.loadAll().map { it.id } shouldBeEqualTo ids
    }

    @Test
    fun remove_givenExistingKey_expectEntryRemoved() {
        val store = newStore()
        val keep = entry("keep")
        val drop = entry("drop")
        store.append(keep)
        store.append(drop)

        store.remove(drop.id)

        val remaining = store.loadAll()
        remaining.size shouldBeEqualTo 1
        remaining[0] shouldBeEqualTo keep
    }

    @Test
    fun remove_givenUnknownKey_expectNoOp() {
        val store = newStore()
        val keep = entry("keep")
        store.append(keep)

        store.remove("not-a-real-id")

        store.loadAll() shouldBeEqualTo listOf(keep)
    }

    @Test
    fun removeAll_keys_givenSubset_expectOnlyMatchingRemoved() {
        val store = newStore()
        val keep = entry("keep")
        val r1 = entry("r1")
        val r2 = entry("r2")
        listOf(r1, keep, r2).forEach { store.append(it) }

        store.removeAll(listOf(r1.id, r2.id))

        store.loadAll() shouldBeEqualTo listOf(keep)
    }

    @Test
    fun removeAll_keys_givenEmptyCollection_expectNoOp() {
        val store = newStore()
        val keep = entry("keep")
        store.append(keep)

        store.removeAll(emptyList<String>())

        store.loadAll() shouldBeEqualTo listOf(keep)
    }

    @Test
    fun removeAll_keys_givenNoMatch_expectStoreUnchanged() {
        val store = newStore()
        val kept = listOf(entry("a"), entry("b"))
        kept.forEach { store.append(it) }

        store.removeAll(listOf("x", "y"))

        store.loadAll() shouldBeEqualTo kept
    }

    @Test
    fun removeAll_keys_givenEntryAppendedAfterLoad_expectAppendedEntrySurvives() {
        val store = newStore()
        val loaded = entry("loaded")
        store.append(loaded)

        // Mirror the handoff sequence: snapshot keys, then a fresh entry lands,
        // then removeAll(snapshottedKeys). The fresh entry must survive.
        val snapshot = store.loadAll().map { it.id }
        val midFlush = entry("midflush")
        store.append(midFlush)
        store.removeAll(snapshot)

        store.loadAll() shouldBeEqualTo listOf(midFlush)
    }

    @Test
    fun removeAll_givenPopulatedStore_expectEmptyAfter() {
        val store = newStore()
        repeat(5) { store.append(entry("e-$it")) }

        store.removeAll()

        store.loadAll().isEmpty().shouldBeTrue()
    }

    @Test
    fun loadAll_givenFreshStore_expectEmpty() {
        val store = newStore()
        store.loadAll().isEmpty().shouldBeTrue()
    }

    @Test
    fun append_givenOverCapacity_expectOldestByTimestampDropped() {
        val cap = 5
        val store = newStore(maxEntries = cap)
        val first = entry("first")
        val second = entry("second")
        store.append(first)
        store.append(second)
        for (i in 0 until cap - 2) store.append(entry("fill-$i"))

        // Filled to capacity; both anchors still present.
        val beforeOverflow = store.loadAll()
        beforeOverflow.size shouldBeEqualTo cap
        beforeOverflow.first() shouldBeEqualTo first

        val last = entry("last")
        store.append(last)

        val afterOverflow = store.loadAll()
        afterOverflow.size shouldBeEqualTo cap
        // Smallest-timestamp entry (first) must have been dropped.
        afterOverflow.none { it.id == first.id }.shouldBeTrue()
        afterOverflow.first() shouldBeEqualTo second
        afterOverflow.last() shouldBeEqualTo last
    }

    @Test
    fun append_givenConcurrentWriters_expectNoLostEntriesAndCapEnforced() {
        val cap = 50
        val store = newStore(maxEntries = cap)
        val threadCount = 8
        val perThread = 20
        val executor = Executors.newFixedThreadPool(threadCount)
        try {
            val tasks = List(threadCount) { threadIndex ->
                executor.submit {
                    repeat(perThread) { i ->
                        store.append(entry("t$threadIndex-$i"))
                    }
                }
            }
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val entries = store.loadAll()
        entries.size shouldBeEqualTo cap
        entries.map { it.id }.toSet().size shouldBeEqualTo entries.size
    }

    @Test
    fun loadAll_givenCorruptOnDiskContent_expectEmptyAndDoesNotThrow() {
        val store = newStore()
        store.append(entry("seed"))
        storeFile().exists().shouldBeTrue()
        storeFile().writeText("this is not valid json {]")

        val entries = store.loadAll()
        entries shouldNotBe null
        entries.isEmpty().shouldBeTrue()
    }

    @Test
    fun remove_givenCorruptedFile_expectStoreFilePreserved() {
        val store = newStore()
        val corrupted = "{not-valid-json"
        storeFile().writeText(corrupted)

        store.remove("any-id")

        storeFile().readText() shouldBeEqualTo corrupted
    }

    @Test
    fun removeAll_keys_givenCorruptedFile_expectStoreFilePreserved() {
        val store = newStore()
        val corrupted = "{not-valid-json"
        storeFile().writeText(corrupted)

        store.removeAll(listOf("a", "b"))

        storeFile().readText() shouldBeEqualTo corrupted
    }

    @Test
    fun append_givenSerializedContent_expectSerializerKeysPresent() {
        val store = newStore()
        val e = entry(id = "serial", payload = "the-payload")

        store.append(e)

        val raw = storeFile().readText()
        raw shouldContain "\"id\":\"serial\""
        raw shouldContain "\"payload\":\"the-payload\""
        raw shouldContain "\"ts\":"
    }
}
