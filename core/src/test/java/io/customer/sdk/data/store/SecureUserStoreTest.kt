package io.customer.sdk.data.store

import io.customer.commontest.core.RobolectricTest
import io.customer.sdk.core.util.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecureUserStoreTest : RobolectricTest() {

    private val mockLogger: Logger = mockk(relaxed = true)

    // Background writes stay pending until the test advances this dispatcher.
    private val writer = StandardTestDispatcher()

    private val crypto: PreferenceCrypto = fakeCrypto()

    private fun fakeCrypto(onEncrypt: () -> Unit = {}): PreferenceCrypto = mockk {
        every { encrypt(any()) } answers {
            onEncrypt()
            "enc:${firstArg<String>()}"
        }
        every { decrypt(any()) } answers { firstArg<String>().removePrefix("enc:") }
    }

    private fun newStore(): SecureUserStoreImpl =
        SecureUserStoreImpl(contextMock, mockLogger, UnconfinedTestDispatcher()).also { it.clearAll() }

    private fun newStoreWithPendingWrites(
        crypto: PreferenceCrypto = this.crypto,
        dispatcher: CoroutineDispatcher = writer
    ): SecureUserStoreImpl =
        SecureUserStoreImpl(contextMock, mockLogger, dispatcher, crypto).also { it.clearAll() }

    @Test
    fun saveUserId_thenGetUserId_expectRoundTrip() {
        val store = newStore()

        store.saveUserId("user-42")

        store.getUserId() shouldBeEqualTo "user-42"
        // A fresh instance reads the same persisted value (receiver/worker cold start).
        SecureUserStoreImpl(contextMock, mockLogger, UnconfinedTestDispatcher()).getUserId() shouldBeEqualTo "user-42"
    }

    @Test
    fun saveUserId_givenNull_expectStoredValueRemoved() {
        val store = newStore()
        store.saveUserId("user-42")

        store.saveUserId(null)

        store.getUserId().shouldBeNull()
    }

    @Test
    fun clearAll_expectUserIdGone() {
        val store = newStore()
        store.saveUserId("user-42")

        store.clearAll()

        store.getUserId().shouldBeNull()
    }

    @Test
    fun saveUserIdInBackground_whenWriteLands_expectCallbackFindsUserIdPersisted() {
        val store = newStoreWithPendingWrites()
        store.saveUserId("previous-user")
        var seenInCallback: String? = null

        store.saveUserIdInBackground("user-42") { seenInCallback = store.getUserId() }

        // Not encrypted on the caller's thread; the old value stays until the write lands.
        verify(exactly = 0) { crypto.encrypt("user-42") }
        seenInCallback.shouldBeNull()
        store.getUserId() shouldBeEqualTo "previous-user"

        writer.scheduler.advanceUntilIdle()

        seenInCallback shouldBeEqualTo "user-42"
        store.getUserId() shouldBeEqualTo "user-42"
    }

    @Test
    fun saveUserIdInBackground_givenNull_expectClearedAndCallbackRunsNow() {
        val store = newStoreWithPendingWrites()
        store.saveUserId("user-42")
        var callbackRan = false

        store.saveUserIdInBackground(null) { callbackRan = true }

        callbackRan.shouldBeTrue()
        store.getUserId().shouldBeNull()
    }

    @Test
    fun clearAll_givenBackgroundWritePending_expectWriteAndCallbackDropped() {
        val store = newStoreWithPendingWrites()
        var callbackRan = false
        store.saveUserIdInBackground("user-42") { callbackRan = true }

        store.clearAll()
        writer.scheduler.advanceUntilIdle()

        callbackRan.shouldBeFalse()
        store.getUserId().shouldBeNull()
        // Superseded before the writer ran, so it never touched the Keystore.
        verify(exactly = 0) { crypto.encrypt(any()) }
    }

    @Test
    fun saveUserId_givenOtherUserWhileBackgroundWritePending_expectNewerSaveWins() {
        val store = newStoreWithPendingWrites()
        val published = mutableListOf<String>()
        store.saveUserIdInBackground("user-a") { published += "user-a" }

        store.saveUserId("user-b") { published += "user-b" }
        writer.scheduler.advanceUntilIdle()

        published shouldBeEqualTo listOf("user-b")
        store.getUserId() shouldBeEqualTo "user-b"
    }

    @Test
    fun saveUserId_givenSameUserWhileBackgroundWritePending_expectCallbackAfterWriteWithoutEncryptingAgain() {
        val store = newStoreWithPendingWrites()
        val seenInCallbacks = mutableListOf<String?>()
        store.saveUserIdInBackground("user-42") { seenInCallbacks += store.getUserId() }

        store.saveUserId("user-42") { seenInCallbacks += store.getUserId() }

        // Waits for the pending write rather than publishing before the userId is on disk.
        seenInCallbacks shouldBeEqualTo emptyList()

        writer.scheduler.advanceUntilIdle()

        seenInCallbacks shouldBeEqualTo listOf("user-42", "user-42")
        verify(exactly = 1) { crypto.encrypt("user-42") }
    }

    @Test
    fun saveUserId_givenSameUserAlreadyPersisted_expectCallbackNowWithoutEncryptingAgain() {
        val store = newStoreWithPendingWrites()
        store.saveUserIdInBackground("user-42") {}
        writer.scheduler.advanceUntilIdle()
        var callbackRan = false

        store.saveUserId("user-42") { callbackRan = true }

        callbackRan.shouldBeTrue()
        verify(exactly = 1) { crypto.encrypt("user-42") }
    }

    @Test
    fun saveUserId_givenSameUserAfterClear_expectEncryptedAgain() {
        val store = newStoreWithPendingWrites()
        store.saveUserId("user-42")
        store.clearAll()

        store.saveUserId("user-42")

        store.getUserId() shouldBeEqualTo "user-42"
        verify(exactly = 2) { crypto.encrypt("user-42") }
    }

    @Test
    fun saveUserIdInBackground_whileEncryptionBlocked_expectSaveAndClearNotToWaitOnIt() {
        val encryptEntered = CountDownLatch(1)
        val releaseEncrypt = CountDownLatch(1)
        val blockedCrypto = fakeCrypto {
            encryptEntered.countDown()
            releaseEncrypt.await()
        }
        val store = newStoreWithPendingWrites(crypto = blockedCrypto, dispatcher = Dispatchers.IO)
        val published = CountDownLatch(1)
        store.saveUserIdInBackground("user-42") { published.countDown() }
        encryptEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()

        // The lock is never held across encryption, so these return while the Keystore is stuck.
        val caller = Thread {
            store.saveUserId("user-42") { published.countDown() }
            store.clearAll()
        }
        caller.start()
        caller.join(5_000)
        caller.isAlive.shouldBeFalse()

        releaseEncrypt.countDown()

        // The clear superseded the blocked write, so neither the write nor its callbacks land.
        published.await(1, TimeUnit.SECONDS).shouldBeFalse()
        store.getUserId().shouldBeNull()
        verify(exactly = 1) { blockedCrypto.encrypt("user-42") }
    }
}
