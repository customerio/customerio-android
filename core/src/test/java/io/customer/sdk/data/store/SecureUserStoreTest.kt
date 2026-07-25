package io.customer.sdk.data.store

import io.customer.commontest.core.RobolectricTest
import io.customer.sdk.core.util.Logger
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecureUserStoreTest : RobolectricTest() {

    private val mockLogger: Logger = mockk(relaxed = true)

    private fun newStore(): SecureUserStoreImpl =
        SecureUserStoreImpl(contextMock, mockLogger).also { it.clearAll() }

    @Test
    fun saveUserId_thenGetUserId_expectRoundTrip() {
        val store = newStore()

        store.saveUserId("user-42")

        store.getUserId() shouldBeEqualTo "user-42"
        // A fresh instance reads the same persisted value (receiver/worker cold start).
        SecureUserStoreImpl(contextMock, mockLogger).getUserId() shouldBeEqualTo "user-42"
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
}
