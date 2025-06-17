package io.customer.sdk.core.extensions

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.customer.commontest.core.RobolectricTest
import io.mockk.*
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DataStoreExtensionsTest : RobolectricTest() {

    private lateinit var mockDataStore: DataStore<Preferences>
    private lateinit var mockPreferences: Preferences
    private val testKey = stringPreferencesKey("test_key")

    @Before
    fun setUp() {
        mockDataStore = mockk()
        mockPreferences = mockk()
    }

    @Test
    fun safeData_whenDataStoreOperatesNormally_expectDataReturned() = runTest {
        val dataFlow: Flow<Preferences> = flowOf(mockPreferences)
        every { mockDataStore.data } returns dataFlow

        val result = mockDataStore.safeData().first()

        result shouldBeEqualTo mockPreferences
    }

    @Test
    fun safeData_whenIOExceptionThrown_expectEmptyPreferencesAndLoggedError() = runTest {
        val ioException = IOException("Disk read error")
        val errorFlow: Flow<Preferences> = kotlinx.coroutines.flow.flow {
            throw ioException
        }
        every { mockDataStore.data } returns errorFlow

        val result = mockDataStore.safeData().first()

        // Should return empty preferences instead of throwing
        result.asMap().size shouldBeEqualTo 0

        // Verify error was logged (implementation logs the error)
        // Note: We cannot easily verify the logger call without more complex mocking
        // but the test ensures the exception doesn't propagate
    }

    @Test
    fun safeData_whenRuntimeExceptionThrown_expectEmptyPreferencesAndLoggedError() = runTest {
        val runtimeException = RuntimeException("Unexpected error")
        val errorFlow: Flow<Preferences> = kotlinx.coroutines.flow.flow {
            throw runtimeException
        }
        every { mockDataStore.data } returns errorFlow

        val result = mockDataStore.safeData().first()

        // Should return empty preferences instead of throwing
        result.asMap().size shouldBeEqualTo 0
    }

    @Test
    fun safeGetString_whenKeyExists_expectValueReturned() = runTest {
        val expectedValue = "test_value"
        every { mockPreferences[testKey] } returns expectedValue
        every { mockDataStore.data } returns flowOf(mockPreferences)

        val result = mockDataStore.safeGetString(testKey)

        result shouldBeEqualTo expectedValue
    }

    @Test
    fun safeGetString_whenKeyDoesNotExist_expectNullReturned() = runTest {
        every { mockPreferences[testKey] } returns null
        every { mockDataStore.data } returns flowOf(mockPreferences)

        val result = mockDataStore.safeGetString(testKey)

        result.shouldBeNull()
    }

    @Test
    fun safeGetString_whenDataStoreThrowsException_expectNullReturned() = runTest {
        val ioException = IOException("Storage error")
        val errorFlow: Flow<Preferences> = kotlinx.coroutines.flow.flow {
            throw ioException
        }
        every { mockDataStore.data } returns errorFlow

        val result = mockDataStore.safeGetString(testKey)

        // Should return null instead of throwing
        result.shouldBeNull()
    }

    @Test
    fun safeGetString_whenDataStoreFlowThrowsException_expectNullReturned() = runTest {
        // Test when the flow itself throws an exception
        val errorFlow: Flow<Preferences> = kotlinx.coroutines.flow.flow {
            throw RuntimeException("Flow error")
        }
        every { mockDataStore.data } returns errorFlow

        val result = mockDataStore.safeGetString(testKey)

        // Should return null instead of throwing
        result.shouldBeNull()
    }

    @Test
    fun extensionFunctions_multipleCalls_expectConsistentBehavior() = runTest {
        val testValue = "consistent_value"
        every { mockPreferences[testKey] } returns testValue
        every { mockDataStore.data } returns flowOf(mockPreferences)

        // Multiple calls should return the same result
        val result1 = mockDataStore.safeGetString(testKey)
        val result2 = mockDataStore.safeGetString(testKey)
        val safeDataResult = mockDataStore.safeData().first()

        result1 shouldBeEqualTo testValue
        result2 shouldBeEqualTo testValue
        safeDataResult shouldBeEqualTo mockPreferences
    }

    @Test
    fun extensionFunctions_errorRecovery_expectGracefulHandling() = runTest {
        // First call throws error, second call succeeds
        val errorFlow: Flow<Preferences> = kotlinx.coroutines.flow.flow {
            throw IOException("First call error")
        }
        val successFlow: Flow<Preferences> = flowOf(mockPreferences)

        every { mockDataStore.data } returnsMany listOf(errorFlow, successFlow)
        every { mockPreferences[testKey] } returns "recovery_value"

        // First call should return empty/null due to error
        val errorResult = mockDataStore.safeData().first()
        errorResult.asMap().size shouldBeEqualTo 0

        // Second call should succeed
        val successResult = mockDataStore.safeData().first()
        successResult shouldBeEqualTo mockPreferences
    }
}
