package io.customer.sdk.data.store

import io.customer.commontest.core.RobolectricTest
import io.customer.sdk.core.util.Logger
import io.mockk.mockk
import java.util.concurrent.Executor
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecureUserStoreTest : RobolectricTest() {

    private val mockLogger: Logger = mockk(relaxed = true)
    private val queuedExecutor = QueuedExecutor()

    private fun newStore(): SecureUserStoreImpl =
        SecureUserStoreImpl(contextMock, mockLogger, queuedExecutor).also {
            it.clearAll()
            queuedExecutor.runAll()
        }

    // Reads only what reached disk, like a receiver/worker cold start.
    private fun freshStore(): SecureUserStoreImpl =
        SecureUserStoreImpl(contextMock, mockLogger, queuedExecutor)

    @Test
    fun saveUserId_thenGetUserId_expectRoundTrip() {
        val store = newStore()

        store.saveUserId("user-42")
        queuedExecutor.runAll()

        store.getUserId() shouldBeEqualTo "user-42"
        freshStore().getUserId() shouldBeEqualTo "user-42"
    }

    @Test
    fun saveUserId_givenNull_expectStoredValueRemoved() {
        val store = newStore()
        store.saveUserId("user-42")

        store.saveUserId(null)
        queuedExecutor.runAll()

        store.getUserId().shouldBeNull()
        freshStore().getUserId().shouldBeNull()
    }

    @Test
    fun clearAll_expectUserIdGone() {
        val store = newStore()
        store.saveUserId("user-42")

        store.clearAll()
        queuedExecutor.runAll()

        store.getUserId().shouldBeNull()
        freshStore().getUserId().shouldBeNull()
    }

    @Test
    fun saveUserId_givenPersistNotRun_expectCallerReadsValueAndDiskUntouched() {
        val store = newStore()

        store.saveUserId("user-42")

        store.getUserId() shouldBeEqualTo "user-42"
        freshStore().getUserId().shouldBeNull()
    }

    @Test
    fun clearAll_givenSaveStillQueued_expectClearNotOvertaken() {
        val store = newStore()
        store.saveUserId("user-42")

        store.clearAll()

        store.getUserId().shouldBeNull()
        queuedExecutor.runAll()
        freshStore().getUserId().shouldBeNull()
    }

    @Test
    fun clearAll_givenOlderValueOnDisk_expectCallerReadsNullBeforePersist() {
        val store = newStore()
        store.saveUserId("user-42")
        queuedExecutor.runAll()

        store.clearAll()

        store.getUserId().shouldBeNull()
    }

    @Test
    fun saveUserId_givenNullAndOlderValueOnDisk_expectCallerReadsNullBeforePersist() {
        val store = newStore()
        store.saveUserId("user-42")
        queuedExecutor.runAll()

        store.saveUserId(null)

        store.getUserId().shouldBeNull()
    }

    @Test
    fun saveUserId_givenOlderValueOnDisk_expectNewValueWins() {
        val store = newStore()
        store.saveUserId("user-a")
        queuedExecutor.runAll()
        val reloaded = freshStore()

        reloaded.saveUserId("user-b")

        reloaded.getUserId() shouldBeEqualTo "user-b"
        queuedExecutor.runAll()
        freshStore().getUserId() shouldBeEqualTo "user-b"
    }

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}
