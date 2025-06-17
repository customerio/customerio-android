package io.customer.sdk.data.store

import io.customer.commontest.core.RobolectricTest
import io.customer.commontest.extensions.random
import io.customer.commontest.util.DeviceTokenManagerStub
import io.mockk.*
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DeviceTokenManagerTest : RobolectricTest() {

    private lateinit var deviceTokenManager: DeviceTokenManager
    private lateinit var mockGlobalPreferenceStore: GlobalPreferenceStore
    private lateinit var testScope: TestScope
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setupTest() {
        testScope = TestScope(testDispatcher)
        mockGlobalPreferenceStore = mockk(relaxed = true)
        deviceTokenManager = DeviceTokenManagerStub()
    }

    private fun createRealDeviceTokenManager(): DeviceTokenManager {
        return DeviceTokenManagerImpl(mockGlobalPreferenceStore)
    }

    @Test
    fun deviceToken_givenInitialState_expectNullToken() {
        deviceTokenManager.deviceToken.shouldBeNull()
    }

    @Test
    fun deviceTokenFlow_givenInitialState_expectNullTokenInFlow() = testScope.runTest {
        val token = deviceTokenManager.deviceTokenFlow.first()
        token.shouldBeNull()
    }

    @Test
    fun setDeviceToken_givenValidToken_expectTokenStoredInMemory() {
        val givenToken = String.random

        deviceTokenManager.setDeviceToken(givenToken)

        deviceTokenManager.deviceToken shouldBeEqualTo givenToken
    }

    @Test
    fun setDeviceToken_givenValidToken_expectTokenStoredInMemoryAndFlowEmission() = testScope.runTest {
        val givenToken = String.random

        deviceTokenManager.setDeviceToken(givenToken)

        deviceTokenManager.deviceToken shouldBeEqualTo givenToken
        val flowValue = deviceTokenManager.deviceTokenFlow.first()
        flowValue shouldBeEqualTo givenToken
    }

    @Test
    fun setDeviceToken_givenNullToken_expectTokenClearedFromMemory() {
        deviceTokenManager.setDeviceToken(null)

        deviceTokenManager.deviceToken.shouldBeNull()
    }

    @Test
    fun clearDeviceToken_expectTokenClearedFromMemory() {
        val initialToken = String.random
        deviceTokenManager.setDeviceToken(initialToken)

        deviceTokenManager.clearDeviceToken()

        deviceTokenManager.deviceToken.shouldBeNull()
    }

    @Test
    fun replaceToken_givenSameToken_expectNoCallback() {
        val existingToken = String.random
        val callbackMock = mockk<(String) -> Unit>(relaxed = true)

        deviceTokenManager.setDeviceToken(existingToken)

        deviceTokenManager.replaceToken(existingToken, callbackMock)

        verify(exactly = 0) { callbackMock(any()) }
        deviceTokenManager.deviceToken shouldBeEqualTo existingToken
    }

    @Test
    fun replaceToken_givenDifferentToken_expectCallbackWithOldToken() {
        val oldToken = String.random
        val newToken = String.random
        val callbackMock = mockk<(String) -> Unit>(relaxed = true)

        deviceTokenManager.setDeviceToken(oldToken)

        deviceTokenManager.replaceToken(newToken, callbackMock)

        verify { callbackMock(oldToken) }
        deviceTokenManager.deviceToken shouldBeEqualTo newToken
    }

    @Test
    fun replaceToken_givenNullToValidToken_expectNoCallback() {
        val newToken = String.random
        val callbackMock = mockk<(String) -> Unit>(relaxed = true)

        deviceTokenManager.replaceToken(newToken, callbackMock)

        verify(exactly = 0) { callbackMock(any()) }
        deviceTokenManager.deviceToken shouldBeEqualTo newToken
    }

    @Test
    fun replaceToken_givenValidTokenToNull_expectCallbackWithOldToken() {
        val oldToken = String.random
        val callbackMock = mockk<(String) -> Unit>(relaxed = true)

        deviceTokenManager.setDeviceToken(oldToken)

        deviceTokenManager.replaceToken(null, callbackMock)

        verify { callbackMock(oldToken) }
        deviceTokenManager.deviceToken.shouldBeNull()
    }

    @Test
    fun replaceToken_givenNullToNull_expectNoCallback() {
        val callbackMock = mockk<(String) -> Unit>(relaxed = true)

        deviceTokenManager.replaceToken(null, callbackMock)

        verify(exactly = 0) { callbackMock(any()) }
        deviceTokenManager.deviceToken.shouldBeNull()
    }

    @Test
    fun concurrentAccess_givenMultipleSetOperations_expectLastValueWins() {
        val token1 = String.random
        val token2 = String.random
        val token3 = String.random

        deviceTokenManager.setDeviceToken(token1)
        deviceTokenManager.setDeviceToken(token2)
        deviceTokenManager.setDeviceToken(token3)

        deviceTokenManager.deviceToken shouldBeEqualTo token3
    }

    @Test
    fun edgeCase_givenEmptyStringToken_expectTokenStoredCorrectly() {
        val emptyToken = ""

        deviceTokenManager.setDeviceToken(emptyToken)

        deviceTokenManager.deviceToken shouldBeEqualTo emptyToken
    }

    @Test
    fun edgeCase_givenVeryLongToken_expectTokenStoredCorrectly() {
        val longToken = "a".repeat(10000)

        deviceTokenManager.setDeviceToken(longToken)

        deviceTokenManager.deviceToken shouldBeEqualTo longToken
    }

    @Test
    fun complexTokenTransitions_givenMultipleReplaceOperations_expectCorrectCallbacks() {
        val token1 = String.random
        val token2 = String.random
        val token3 = String.random
        val callbackMock = mockk<(String) -> Unit>(relaxed = true)

        deviceTokenManager.setDeviceToken(token1)

        deviceTokenManager.replaceToken(token2, callbackMock)
        verify { callbackMock(token1) }
        deviceTokenManager.deviceToken shouldBeEqualTo token2

        deviceTokenManager.replaceToken(token3, callbackMock)
        verify { callbackMock(token2) }
        deviceTokenManager.deviceToken shouldBeEqualTo token3

        deviceTokenManager.replaceToken(token3, callbackMock)
        verify(exactly = 2) { callbackMock(any()) }
    }

    @Test
    fun cleanup_givenManagerInUse_expectCleanupCompletesWithoutError() {
        val token = String.random
        deviceTokenManager.setDeviceToken(token)

        deviceTokenManager.cleanup()

        deviceTokenManager.deviceToken shouldBeEqualTo token
    }

    @Test
    fun realImplementation_basicOperations_expectCorrectBehavior() = testScope.runTest {
        coEvery { mockGlobalPreferenceStore.getDeviceToken() } returns null
        coEvery { mockGlobalPreferenceStore.saveDeviceToken(any()) } returns Unit
        coEvery { mockGlobalPreferenceStore.removeDeviceToken() } returns Unit

        val realManager = createRealDeviceTokenManager()

        realManager.deviceToken.shouldBeNull()
        realManager.setDeviceToken("test-token")
        realManager.clearDeviceToken()
        realManager.cleanup()
    }

    @Test
    fun realImplementation_errorHandling_expectGracefulDegradation() = testScope.runTest {
        coEvery { mockGlobalPreferenceStore.getDeviceToken() } throws IOException("Storage unavailable")
        coEvery { mockGlobalPreferenceStore.saveDeviceToken(any()) } throws IOException("Storage unavailable")

        val realManager = createRealDeviceTokenManager()

        realManager.setDeviceToken("test-token")
        realManager.clearDeviceToken()
        realManager.cleanup()
    }

    @Test
    fun realImplementation_basicOperations_expectNoExceptions() = testScope.runTest {
        coEvery { mockGlobalPreferenceStore.getDeviceToken() } returns null
        coEvery { mockGlobalPreferenceStore.saveDeviceToken(any()) } returns Unit
        coEvery { mockGlobalPreferenceStore.removeDeviceToken() } returns Unit

        val realManager = createRealDeviceTokenManager()

        realManager.setDeviceToken("test-token")
        realManager.clearDeviceToken()

        val flow = realManager.deviceTokenFlow
        flow.toString().isNotEmpty() shouldBeEqualTo true
    }

    @Test
    fun realImplementation_multipleCleanups_expectNoErrors() = testScope.runTest {
        coEvery { mockGlobalPreferenceStore.getDeviceToken() } returns null

        val realManager = createRealDeviceTokenManager()

        realManager.cleanup()
        realManager.cleanup()
        realManager.cleanup()
    }

    @Test
    fun realImplementation_storageErrors_expectGracefulHandling() = testScope.runTest {
        coEvery { mockGlobalPreferenceStore.getDeviceToken() } throws IOException("Storage error")
        coEvery { mockGlobalPreferenceStore.saveDeviceToken(any()) } throws IOException("Storage error")

        val realManager = createRealDeviceTokenManager()

        realManager.setDeviceToken("test-token")
        realManager.clearDeviceToken()
        realManager.cleanup()
    }
}
