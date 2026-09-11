package io.customer.sdk.data.store

import io.customer.commontest.core.RobolectricTest
import io.customer.sdk.core.util.Logger
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PendingDeliveryStoreTest : RobolectricTest() {

    private val mockLogger: Logger = mockk(relaxed = true)
    private val fileName = "cio_test_pending_delivery.json"

    @Serializable
    private data class TestEntry(
        val id: String,
        val payload: String
    ) : PendingDeliveryStore.PendingDeliveryEntry {
        override val key: String get() = id
    }

    private fun entry(id: String, payload: String = "p-$id"): TestEntry =
        TestEntry(id = id, payload = payload)

    private fun newStore(maxEntries: Int = PendingDeliveryStore.DEFAULT_MAX_ENTRIES) =
        PendingDeliveryStore(
            context = contextMock,
            fileName = fileName,
            elementSerializer = TestEntry.serializer(),
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
    fun get_givenExistingKey_expectMatchingEntry() {
        val store = newStore()
        val target = entry("target")
        store.appendAll(listOf(entry("other"), target))

        store.get(target.key) shouldBeEqualTo target
    }

    @Test
    fun get_givenAbsentKey_expectNull() {
        val store = newStore()
        store.append(entry("other"))

        store.get("missing") shouldBeEqualTo null
    }

    @Test
    fun appendAll_givenMultipleEntries_expectAllPersistedAfterExisting() {
        val store = newStore()
        store.append(entry("a"))

        store.appendAll(listOf(entry("b"), entry("c")))

        store.loadAll().map { it.id } shouldBeEqualTo listOf("a", "b", "c")
    }

    @Test
    fun appendAll_givenExistingStableKeys_expectAtomicReplacementWithoutDuplicates() {
        val store = newStore()
        store.appendAll(listOf(entry("a", "old"), entry("b", "keep")))

        store.appendAll(listOf(entry("a", "recovered")))

        store.loadAll() shouldBeEqualTo listOf(entry("b", "keep"), entry("a", "recovered"))
    }

    @Test
    fun appendAll_givenWriteSucceeds_expectTrue() {
        val store = newStore()

        store.appendAll(listOf(entry("a"))) shouldBeEqualTo true
    }

    @Test
    fun appendAll_givenWriteFails_expectFalse() {
        val store = newStore()
        // Force the write to fail by turning the backing file path into a directory.
        storeFile().delete()
        storeFile().mkdirs()

        store.appendAll(listOf(entry("a"))) shouldBeEqualTo false
    }

    @Test
    fun appendAll_givenEmpty_expectNoChange() {
        val store = newStore()
        store.append(entry("a"))

        store.appendAll(emptyList())

        store.loadAll().map { it.id } shouldBeEqualTo listOf("a")
    }

    @Test
    fun appendAll_givenOverCapacity_expectOldestEvicted() {
        val store = newStore(maxEntries = 2)

        store.appendAll(listOf(entry("a"), entry("b"), entry("c")))

        store.loadAll().map { it.id } shouldBeEqualTo listOf("b", "c")
    }

    @Test
    fun remove_givenExistingKey_expectEntryRemoved() {
        val store = newStore()
        val keep = entry("keep")
        val drop = entry("drop")
        store.append(keep)
        store.append(drop)

        store.remove(drop.key) shouldBeEqualTo true

        val remaining = store.loadAll()
        remaining.size shouldBeEqualTo 1
        remaining[0] shouldBeEqualTo keep
    }

    @Test
    fun claim_givenPresentKey_expectRemovedAndReturnsTrue() {
        val store = newStore()
        val keep = entry("keep")
        val target = entry("target")
        store.append(keep)
        store.append(target)

        store.claim(target.key) shouldBeEqualTo true

        store.loadAll() shouldBeEqualTo listOf(keep)
    }

    @Test
    fun claim_givenAbsentKey_expectFalseAndStoreUnchanged() {
        val store = newStore()
        val keep = entry("keep")
        store.append(keep)

        store.claim("not-a-real-id") shouldBeEqualTo false

        store.loadAll() shouldBeEqualTo listOf(keep)
    }

    @Test
    fun claim_givenSameKeyTwice_expectOnlyFirstClaimWins() {
        val store = newStore()
        val target = entry("target")
        store.append(target)

        store.claim(target.key) shouldBeEqualTo true
        store.claim(target.key) shouldBeEqualTo false
    }

    @Test
    fun claim_givenRemovingWriteFails_expectFalseAndEntryPreserved() {
        val store = newStore()
        val target = entry("target")
        store.append(target)
        // Entry is readable, but the atomic write can't stage its temp file while the store's
        // directory is read-only, so the claim must fail and leave the row for the other channel.
        val dir = contextMock.applicationContext.filesDir
        dir.setReadOnly()
        try {
            store.claim(target.key) shouldBeEqualTo false
        } finally {
            dir.setWritable(true)
        }

        store.loadAll() shouldBeEqualTo listOf(target)
    }

    @Test
    fun remove_givenUnknownKey_expectNoOp() {
        val store = newStore()
        val keep = entry("keep")
        store.append(keep)

        // Already absent counts as removed: the caller's postcondition — "this key is not queued" — holds.
        store.remove("not-a-real-id") shouldBeEqualTo true

        store.loadAll() shouldBeEqualTo listOf(keep)
    }

    @Test
    fun remove_givenWriteFailure_expectFalseAndEntryStillQueued() {
        // A caller that removes to mark work done must be able to tell this apart from success, or it
        // will treat the still-queued entry as gone and repeat that work on its next pass.
        val store = newStore()
        val stuck = entry("stuck")
        store.append(stuck)

        val dir = contextMock.applicationContext.filesDir
        dir.setReadOnly()
        val removed = try {
            store.remove(stuck.key)
        } finally {
            dir.setWritable(true)
        }

        removed shouldBeEqualTo false
        store.loadAll() shouldBeEqualTo listOf(stuck)
    }

    @Test
    fun removeAll_keys_givenSubset_expectOnlyMatchingRemoved() {
        val store = newStore()
        val keep = entry("keep")
        val r1 = entry("r1")
        val r2 = entry("r2")
        listOf(r1, keep, r2).forEach { store.append(it) }

        store.removeAll(listOf(r1.key, r2.key))

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
        val snapshot = store.loadAll().map { it.key }
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
    fun append_givenOverCapacity_expectHeadOfInsertionOrderDropped() {
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
        // FIFO eviction: the oldest entry (head of the insertion order) is dropped.
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
    fun loadAll_givenOneUndecodableRow_expectTheOthersSurvive() {
        val store = newStore()
        storeFile().writeText(
            """[{"id":"a","payload":"p-a"},{"unexpected":true},{"id":"c","payload":"p-c"}]"""
        )

        store.loadAll().map { it.id } shouldBeEqualTo listOf("a", "c")
    }

    @Test
    fun append_givenOneUndecodableRow_expectTheOthersKept() {
        // The whole point: a single bad row used to make the next append rewrite the file
        // without any of the rows that were still queued behind it.
        val store = newStore()
        storeFile().writeText(
            """[{"id":"a","payload":"p-a"},{"unexpected":true},{"id":"c","payload":"p-c"}]"""
        )

        store.append(entry("d")).shouldBeTrue()

        store.loadAll().map { it.id } shouldBeEqualTo listOf("a", "c", "d")
    }

    @Test
    fun append_givenUnreadableFile_expectRefusedAndNothingWritten() {
        val store = newStore()
        store.append(entry("queued")).shouldBeTrue()
        // A directory in the file's place fails the read the way an IO error would, without
        // depending on filesystem permissions that vary by machine.
        storeFile().delete()
        storeFile().mkdirs()

        store.append(entry("new")) shouldBeEqualTo false

        storeFile().isDirectory.shouldBeTrue()
        // The refusal has to happen before the write is attempted. Asserting only on the false
        // return would pass without it too, since renaming over a directory fails anyway — so
        // assert the write was never tried.
        verify(exactly = 0) {
            mockLogger.error(match { it.contains("Failed to write") }, any(), any())
        }
    }

    @Test
    fun remove_givenUnreadableFile_expectNotReportedAsRemoved() {
        // Reporting true here tells a caller its delivery is done while the row is still on disk.
        val store = newStore()
        storeFile().delete()
        storeFile().mkdirs()

        store.remove("any-id") shouldBeEqualTo false
    }

    @Test
    fun claim_givenUnreadableFile_expectNotClaimed() {
        // A claim that succeeds without removing the row lets a second channel claim it too, and
        // push metrics carry no dedup id, so that double-counts.
        val store = newStore()
        storeFile().delete()
        storeFile().mkdirs()

        store.claim("any-id") shouldBeEqualTo false
    }

    @Test
    fun contains_givenUnreadableFile_expectUnknownRatherThanAbsent() {
        // A claim helper reads absent as "another channel delivered it", so unknown must not
        // collapse to absent — that reports a delivery that never happened.
        val store = newStore()
        storeFile().delete()
        storeFile().mkdirs()

        store.contains("any-id") shouldBeEqualTo null
    }

    @Test
    fun contains_givenReadableFile_expectPresenceReported() {
        val store = newStore()
        store.append(entry("here"))

        store.contains("here") shouldBeEqualTo true
        store.contains("absent") shouldBeEqualTo false
    }

    @Test
    fun append_givenSerializedContent_expectFieldsPresent() {
        val store = newStore()
        val e = entry(id = "serial", payload = "the-payload")

        store.append(e)

        val raw = storeFile().readText()
        raw shouldContain "\"id\":\"serial\""
        raw shouldContain "\"payload\":\"the-payload\""
    }
}
