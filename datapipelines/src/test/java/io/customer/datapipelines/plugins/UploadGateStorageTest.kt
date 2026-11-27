package io.customer.datapipelines.plugins

import com.segment.analytics.kotlin.core.Storage
import io.customer.commontest.core.JUnit5Test
import io.customer.sdk.core.util.Clock
import io.mockk.every
import io.mockk.mockk
import kotlin.random.Random
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

class UploadGateStorageTest : JUnit5Test() {
    private val queue = mutableListOf<String>()
    private var now = 1_000L
    private val backoffs = mutableListOf<Long>()
    private val probeIndexes = ArrayDeque<Int>()

    private val storage = mockk<Storage>(relaxed = true).also {
        every { it.read(Storage.Constants.Events) } answers { queue.joinToString(", ") }
        every { it.read(Storage.Constants.UserId) } returns "user-1"
    }
    private val clock = mockk<Clock>().also {
        every { it.elapsedRealtime() } answers { now }
    }

    // No jitter; picks probe files from probeIndexes (first file when empty)
    private val random = object : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextInt(until: Int): Int = probeIndexes.removeFirstOrNull() ?: 0
    }
    private val gate = UploadGateStorage(storage, clock, random, { backoffs += it })

    private fun flushSees(): List<String> = gate.read(Storage.Constants.Events)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()

    private fun failOnce() {
        flushSees()
        flushSees()
    }

    @Test
    fun read_givenOtherKey_expectForwardedUnchanged() {
        gate.read(Storage.Constants.UserId) shouldBeEqualTo "user-1"
    }

    @Test
    fun read_givenNoFailures_expectEveryFile() {
        queue += listOf("f1", "f2", "f3")

        flushSees() shouldBeEqualTo listOf("f1", "f2", "f3")
        queue.clear()
        queue += "f4"
        flushSees() shouldBeEqualTo listOf("f4")
        backoffs shouldBeEqualTo emptyList()
    }

    @Test
    fun read_givenSomeFilesUploaded_expectNoBackoff() {
        queue += listOf("f1", "f2", "f3")
        flushSees()
        queue.remove("f2")

        flushSees() shouldBeEqualTo listOf("f1", "f3")
        backoffs shouldBeEqualTo emptyList()
    }

    @Test
    fun read_givenNoFileUploaded_expectNoFilesUntilBackoffEndsThenOne() {
        queue += listOf("f1", "f2")
        flushSees()

        now += 1_000
        flushSees() shouldBeEqualTo emptyList()
        backoffs shouldBeEqualTo listOf(30_000L)

        // Backoff counts from the failed attempt at 1_000
        now = 1_000 + 29_999
        flushSees() shouldBeEqualTo emptyList()
        now = 1_000 + 30_000
        flushSees() shouldBeEqualTo listOf("f1")
    }

    @Test
    fun read_givenNextFlushAfterBackoffAlreadyEnded_expectProbeOnThatFlush() {
        queue += listOf("f1", "f2")
        flushSees()

        now += 60 * 60 * 1000L
        flushSees() shouldBeEqualTo listOf("f1")
    }

    @Test
    fun read_givenRepeatedFailures_expectBackoffDoublesUpToFiveMinutes() {
        queue += "f1"
        flushSees()
        repeat(6) {
            flushSees()
            now += backoffs.last()
            flushSees()
        }

        backoffs shouldBeEqualTo listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L)
    }

    @Test
    fun read_givenProbeUploaded_expectEveryFileAndBackoffReset() {
        queue += listOf("f1", "f2", "f3")
        failOnce()
        now += backoffs.last()
        flushSees() shouldBeEqualTo listOf("f1")
        queue.remove("f1")

        flushSees() shouldBeEqualTo listOf("f2", "f3")
        flushSees()
        backoffs.last() shouldBeEqualTo 30_000L
    }

    @Test
    fun read_givenOneFileKeepsFailing_expectOtherFilesStillProbed() {
        queue += listOf("bad", "f2")
        failOnce()
        now += backoffs.last()
        probeIndexes += listOf(0, 1)
        flushSees() shouldBeEqualTo listOf("bad")

        flushSees()
        now += backoffs.last()
        flushSees() shouldBeEqualTo listOf("f2")
        queue.remove("f2")

        flushSees() shouldBeEqualTo listOf("bad")
    }
}
