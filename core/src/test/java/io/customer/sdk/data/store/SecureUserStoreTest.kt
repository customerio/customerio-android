package io.customer.sdk.data.store

import io.customer.commontest.core.RobolectricTest
import io.customer.sdk.core.util.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
        SecureUserStoreImpl(contextMock, mockLogger, queuedExecutor).also { it.clearAll() }

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
    fun saveUserId_givenPersistNotRun_expectCallerReadsValueAndDiskUntouched() {
        val store = newStore()

        store.saveUserId("user-42")

        store.getUserId() shouldBeEqualTo "user-42"
        freshStore().getUserId().shouldBeNull()
    }

    @Test
    fun saveUserId_givenNull_expectDiskClearedWithoutQueue() {
        val store = newStore()
        store.saveUserId("user-42")
        queuedExecutor.runAll()

        store.saveUserId(null)

        store.getUserId().shouldBeNull()
        freshStore().getUserId().shouldBeNull()
    }

    @Test
    fun clearAll_expectDiskClearedWithoutQueue() {
        val store = newStore()
        store.saveUserId("user-42")
        queuedExecutor.runAll()

        store.clearAll()

        store.getUserId().shouldBeNull()
        freshStore().getUserId().shouldBeNull()
    }

    @Test
    fun clearAll_givenSaveStillQueued_expectQueuedSaveDropped() {
        val store = newStore()
        store.saveUserId("user-42")

        store.clearAll()
        queuedExecutor.runAll()

        store.getUserId().shouldBeNull()
        freshStore().getUserId().shouldBeNull()
    }

    @Test
    fun saveUserId_givenSwitchingUsers_expectOldUserRemovedFromDiskBeforePersist() {
        val store = newStore()
        store.saveUserId("user-a")
        queuedExecutor.runAll()

        store.saveUserId("user-b")

        store.getUserId() shouldBeEqualTo "user-b"
        freshStore().getUserId().shouldBeNull()
        queuedExecutor.runAll()
        freshStore().getUserId() shouldBeEqualTo "user-b"
    }

    @Test
    fun saveUserId_givenFirstWriteInProcess_expectDiskKeptUntilPersist() {
        val store = newStore()
        store.saveUserId("user-a")
        queuedExecutor.runAll()
        val relaunched = freshStore()

        relaunched.saveUserId("user-a")

        freshStore().getUserId() shouldBeEqualTo "user-a"
    }

    @Test
    fun saveUserId_givenNewerSaveQueued_expectOnlyLatestLands() {
        val store = newStore()
        store.saveUserId("user-a")
        store.saveUserId("user-b")

        queuedExecutor.runNext()

        freshStore().getUserId().shouldBeNull()
        queuedExecutor.runAll()
        freshStore().getUserId() shouldBeEqualTo "user-b"
    }

    @Test
    fun clearAll_givenDuringEncrypt_expectEncryptedValueDropped() {
        lateinit var store: SecureUserStoreImpl
        val clearingCrypto: PreferenceCrypto = mockk {
            every { encrypt(any()) } answers {
                store.clearAll()
                "encrypted-${firstArg<String>()}"
            }
        }
        store = SecureUserStoreImpl(contextMock, mockLogger, queuedExecutor, clearingCrypto).also { it.clearAll() }
        store.saveUserId("user-42")

        queuedExecutor.runAll()

        store.getUserId().shouldBeNull()
        freshStore().getUserId().shouldBeNull()
    }

    @Test
    fun saveUserId_givenSupersededBeforeEncrypt_expectNoKeystoreCallForIt() {
        val countingCrypto: PreferenceCrypto = mockk {
            every { encrypt(any()) } answers { "encrypted-${firstArg<String>()}" }
        }
        val store = SecureUserStoreImpl(contextMock, mockLogger, queuedExecutor, countingCrypto).also { it.clearAll() }
        store.saveUserId("user-a")
        store.saveUserId("user-b")

        queuedExecutor.runAll()

        verify(exactly = 0) { countingCrypto.encrypt("user-a") }
        verify(exactly = 1) { countingCrypto.encrypt("user-b") }
    }

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().run()
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}
