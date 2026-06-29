package io.customer.sdk.data.store

import io.customer.commontest.core.RobolectricTest
import io.customer.sdk.core.util.Logger
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PendingDeliveryClaimTest : RobolectricTest() {

    private val mockLogger: Logger = mockk(relaxed = true)

    @Serializable
    private data class TestEntry(val id: String) : PendingDeliveryStore.PendingDeliveryEntry {
        override val key: String get() = id
    }

    private fun newStore() = PendingDeliveryStore(
        context = contextMock,
        fileName = "cio_test_claim_send_restore.json",
        elementSerializer = TestEntry.serializer(),
        logger = mockLogger
    ).also { it.removeAll() }

    @Test
    fun claimSendRestore_givenEntryNotPresent_expectAlreadyClaimedAndSendNotInvoked() = runBlocking<Unit> {
        val store = newStore()
        val entry = TestEntry("absent")
        var sendInvoked = false

        val result = store.claimSendRestore(entry) {
            sendInvoked = true
            Result.success(Unit)
        }

        result shouldBeEqualTo PendingDeliveryResult.AlreadyClaimed
        sendInvoked shouldBeEqualTo false
    }

    @Test
    fun claimSendRestore_givenSendSucceeds_expectDeliveredAndEntryRemoved() = runBlocking<Unit> {
        val store = newStore()
        val entry = TestEntry("ok")
        store.append(entry)

        val result = store.claimSendRestore(entry) { Result.success(Unit) }

        result shouldBeEqualTo PendingDeliveryResult.Delivered
        store.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun claimSendRestore_givenIOException_expectRetryableAndEntryRestored() = runBlocking<Unit> {
        val store = newStore()
        val entry = TestEntry("retry")
        store.append(entry)
        val cause = IOException("network down")

        val result = store.claimSendRestore(entry) { Result.failure(cause) }

        result shouldBeInstanceOf PendingDeliveryResult.Retryable::class
        (result as PendingDeliveryResult.Retryable).cause shouldBeEqualTo cause
        store.loadAll() shouldBeEqualTo listOf(entry)
    }

    @Test
    fun claimSendRestore_givenNonRetryableError_expectFailedAndEntryRestored() = runBlocking<Unit> {
        val store = newStore()
        val entry = TestEntry("fail")
        store.append(entry)
        val cause = IllegalStateException("400 bad request")

        val result = store.claimSendRestore(entry) { Result.failure(cause) }

        result shouldBeInstanceOf PendingDeliveryResult.Failed::class
        (result as PendingDeliveryResult.Failed).cause shouldBeEqualTo cause
        store.loadAll() shouldBeEqualTo listOf(entry)
    }

    @Test
    fun claimSendRestore_givenCustomRetryablePredicate_expectRetryableForMatchedError() = runBlocking<Unit> {
        val store = newStore()
        val entry = TestEntry("custom")
        store.append(entry)

        val result = store.claimSendRestore(
            entry = entry,
            isRetryable = { it is IllegalStateException }
        ) { Result.failure(IllegalStateException("transient")) }

        result shouldBeInstanceOf PendingDeliveryResult.Retryable::class
        store.loadAll() shouldBeEqualTo listOf(entry)
    }

    @Test
    fun sendRemoveOnSuccess_givenEntryNotPresent_expectAlreadyClaimedAndSendNotInvoked() = runBlocking<Unit> {
        val store = newStore()
        val entry = TestEntry("absent")
        var sendInvoked = false

        val result = store.sendRemoveOnSuccess(entry) {
            sendInvoked = true
            Result.success(Unit)
        }

        result shouldBeEqualTo PendingDeliveryResult.AlreadyClaimed
        sendInvoked shouldBeEqualTo false
    }

    @Test
    fun sendRemoveOnSuccess_givenSendSucceeds_expectDeliveredAndEntryRemoved() = runBlocking<Unit> {
        val store = newStore()
        val entry = TestEntry("ok")
        store.append(entry)

        val result = store.sendRemoveOnSuccess(entry) { Result.success(Unit) }

        result shouldBeEqualTo PendingDeliveryResult.Delivered
        store.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun sendRemoveOnSuccess_whileSending_expectEntryStillPresentUntilSuccess() = runBlocking<Unit> {
        // The core difference from claimSendRestore: the row is NOT removed before the send, so a
        // process death mid-send leaves it for a retry/flush instead of dropping it.
        val store = newStore()
        val entry = TestEntry("in-flight")
        store.append(entry)

        store.sendRemoveOnSuccess(entry) {
            store.loadAll() shouldBeEqualTo listOf(entry)
            Result.success(Unit)
        }

        store.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun sendRemoveOnSuccess_givenIOException_expectRetryableAndEntryKept() = runBlocking<Unit> {
        val store = newStore()
        val entry = TestEntry("retry")
        store.append(entry)
        val cause = IOException("network down")

        val result = store.sendRemoveOnSuccess(entry) { Result.failure(cause) }

        result shouldBeInstanceOf PendingDeliveryResult.Retryable::class
        (result as PendingDeliveryResult.Retryable).cause shouldBeEqualTo cause
        store.loadAll() shouldBeEqualTo listOf(entry)
    }

    @Test
    fun sendRemoveOnSuccess_givenNonRetryableError_expectFailedAndEntryKept() = runBlocking<Unit> {
        val store = newStore()
        val entry = TestEntry("fail")
        store.append(entry)
        val cause = IllegalStateException("400 bad request")

        val result = store.sendRemoveOnSuccess(entry) { Result.failure(cause) }

        result shouldBeInstanceOf PendingDeliveryResult.Failed::class
        (result as PendingDeliveryResult.Failed).cause shouldBeEqualTo cause
        store.loadAll() shouldBeEqualTo listOf(entry)
    }
}
