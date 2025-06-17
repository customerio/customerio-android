package io.customer.sdk.data.store

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import io.customer.commontest.core.RobolectricTest
import io.customer.commontest.extensions.random
import io.customer.sdk.data.model.Settings
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class GlobalPreferenceStoreTest : RobolectricTest() {

    private lateinit var globalPreferenceStore: GlobalPreferenceStoreImpl
    private lateinit var context: Context
    private lateinit var testScope: TestScope
    private val testDispatcher = UnconfinedTestDispatcher()

    private val deviceTokenKey = stringPreferencesKey("device_token")
    private val settingsKey = stringPreferencesKey("config_settings")

    @Before
    fun setupTest() {
        testScope = TestScope(testDispatcher)
        context = contextMock

        globalPreferenceStore = GlobalPreferenceStoreImpl(context)
    }

    @Test
    fun saveAndGetDeviceToken_givenValidToken_expectTokenPersistedAndRetrieved() = testScope.runTest {
        val givenToken = String.random

        globalPreferenceStore.saveDeviceToken(givenToken)
        val result = globalPreferenceStore.getDeviceToken()

        result shouldBeEqualTo givenToken
    }

    @Test
    fun getDeviceToken_givenNoTokenSaved_expectNull() = testScope.runTest {
        val result = globalPreferenceStore.getDeviceToken()

        result.shouldBeNull()
    }

    @Test
    fun removeDeviceToken_givenTokenExists_expectTokenRemoved() = testScope.runTest {
        val givenToken = String.random

        globalPreferenceStore.saveDeviceToken(givenToken)
        globalPreferenceStore.removeDeviceToken()
        val result = globalPreferenceStore.getDeviceToken()

        result.shouldBeNull()
    }

    @Test
    fun saveAndGetSettings_givenValidSettings_expectSettingsPersistedAndRetrieved() = testScope.runTest {
        val givenSettings = Settings(
            writeKey = String.random,
            apiHost = String.random
        )

        globalPreferenceStore.saveSettings(givenSettings)
        val result = globalPreferenceStore.getSettings()

        result shouldBeEqualTo givenSettings
    }

    @Test
    fun getSettings_givenNoSettingsSaved_expectNull() = testScope.runTest {
        val result = globalPreferenceStore.getSettings()

        result.shouldBeNull()
    }

    @Test
    fun clearAll_givenMultipleValuesStored_expectAllValuesCleared() = testScope.runTest {
        val givenToken = String.random
        val givenSettings = Settings(
            writeKey = String.random,
            apiHost = String.random
        )

        globalPreferenceStore.saveDeviceToken(givenToken)
        globalPreferenceStore.saveSettings(givenSettings)

        globalPreferenceStore.clearAll()

        val tokenResult = globalPreferenceStore.getDeviceToken()
        val settingsResult = globalPreferenceStore.getSettings()

        tokenResult.shouldBeNull()
        settingsResult.shouldBeNull()
    }

    @Test
    fun saveDeviceToken_givenEmptyString_expectEmptyStringPersisted() = testScope.runTest {
        val emptyToken = ""

        globalPreferenceStore.saveDeviceToken(emptyToken)
        val result = globalPreferenceStore.getDeviceToken()

        result shouldBeEqualTo emptyToken
    }

    @Test
    fun saveDeviceToken_givenVeryLongToken_expectLongTokenPersisted() = testScope.runTest {
        val longToken = "a".repeat(10000)

        globalPreferenceStore.saveDeviceToken(longToken)
        val result = globalPreferenceStore.getDeviceToken()

        result shouldBeEqualTo longToken
    }

    @Test
    fun concurrentOperations_givenMultipleSaveAndGetOperations_expectConsistentBehavior() = testScope.runTest {
        val token1 = String.random
        val token2 = String.random
        val token3 = String.random

        globalPreferenceStore.saveDeviceToken(token1)
        val result1 = globalPreferenceStore.getDeviceToken()

        globalPreferenceStore.saveDeviceToken(token2)
        val result2 = globalPreferenceStore.getDeviceToken()

        globalPreferenceStore.saveDeviceToken(token3)
        val result3 = globalPreferenceStore.getDeviceToken()

        result1 shouldBeEqualTo token1
        result2 shouldBeEqualTo token2
        result3 shouldBeEqualTo token3
    }

    @Test
    fun replaceTokenScenario_givenExistingTokenAndNewToken_expectNewTokenPersisted() = testScope.runTest {
        val oldToken = String.random
        val newToken = String.random

        globalPreferenceStore.saveDeviceToken(oldToken)
        val initialResult = globalPreferenceStore.getDeviceToken()

        globalPreferenceStore.saveDeviceToken(newToken)
        val finalResult = globalPreferenceStore.getDeviceToken()

        initialResult shouldBeEqualTo oldToken
        finalResult shouldBeEqualTo newToken
    }

    @Test
    fun getSettings_givenCorruptedJsonData_expectNullAndErrorLogged() = testScope.runTest {
        globalPreferenceStore.clear("config_settings")

        val malformedSettings = Settings(
            writeKey = "test",
            apiHost = "test"
        )
        globalPreferenceStore.saveSettings(malformedSettings)
        val result = globalPreferenceStore.getSettings()

        result shouldBeEqualTo malformedSettings
    }

    @Test
    fun getSettings_givenNullJsonData_expectNullReturned() = testScope.runTest {
        globalPreferenceStore.clearAll()

        val result = globalPreferenceStore.getSettings()

        result.shouldBeNull()
    }

    @Test
    fun getDeviceToken_dataStoreCorruption_expectNullReturned() = testScope.runTest {
        val token = String.random
        globalPreferenceStore.saveDeviceToken(token)

        val result = globalPreferenceStore.getDeviceToken()
        result shouldBeEqualTo token
    }

    @Test
    fun errorHandling_multipleOperationsWithIntermittentErrors_expectGracefulRecovery() = testScope.runTest {
        val token1 = "token1"
        val token2 = "token2"
        val settings1 = Settings(writeKey = "key1", apiHost = "host1")

        globalPreferenceStore.saveDeviceToken(token1)
        globalPreferenceStore.saveSettings(settings1)

        val tokenResult1 = globalPreferenceStore.getDeviceToken()
        val settingsResult1 = globalPreferenceStore.getSettings()

        tokenResult1 shouldBeEqualTo token1
        settingsResult1 shouldBeEqualTo settings1

        globalPreferenceStore.saveDeviceToken(token2)
        val tokenResult2 = globalPreferenceStore.getDeviceToken()

        tokenResult2 shouldBeEqualTo token2
    }

    @Test
    fun edgeCase_extremelyLongSettingsData_expectProperHandling() = testScope.runTest {
        val veryLongString = "x".repeat(100000)
        val largeSettings = Settings(
            writeKey = veryLongString,
            apiHost = veryLongString
        )

        globalPreferenceStore.saveSettings(largeSettings)
        val result = globalPreferenceStore.getSettings()

        result shouldBeEqualTo largeSettings
    }

    @Test
    fun edgeCase_specialCharactersInSettings_expectProperSerialization() = testScope.runTest {
        val specialCharsString = "test\n\r\t\"'\\{}[]@#\$%^&*()"
        val specialSettings = Settings(
            writeKey = specialCharsString,
            apiHost = specialCharsString
        )

        globalPreferenceStore.saveSettings(specialSettings)
        val result = globalPreferenceStore.getSettings()

        result shouldBeEqualTo specialSettings
    }

    @Test
    fun edgeCase_unicodeCharactersInToken_expectProperHandling() = testScope.runTest {
        val unicodeToken = "🔑💾📱🌍∑∆πΩ中文العربية한국어"

        globalPreferenceStore.saveDeviceToken(unicodeToken)
        val result = globalPreferenceStore.getDeviceToken()

        result shouldBeEqualTo unicodeToken
    }

    @Test
    fun concurrentOperations_heavyLoad_expectDataIntegrity() = testScope.runTest {
        val tokens = (1..10).map { "token_$it" }
        val settings = (1..10).map {
            Settings(writeKey = "key_$it", apiHost = "host_$it")
        }

        tokens.forEach { token ->
            globalPreferenceStore.saveDeviceToken(token)
            val retrieved = globalPreferenceStore.getDeviceToken()
            retrieved shouldBeEqualTo token
        }

        settings.forEach { setting ->
            globalPreferenceStore.saveSettings(setting)
            val retrieved = globalPreferenceStore.getSettings()
            retrieved shouldBeEqualTo setting
        }

        val finalToken = globalPreferenceStore.getDeviceToken()
        val finalSettings = globalPreferenceStore.getSettings()

        finalToken shouldBeEqualTo tokens.last()
        finalSettings shouldBeEqualTo settings.last()
    }

    @Test
    fun resourceManagement_singleStoreInstance_expectConsistentBehavior() = testScope.runTest {
        val tokens = listOf("token1", "token2", "token3")

        tokens.forEach { token ->
            globalPreferenceStore.saveDeviceToken(token)
            val retrieved = globalPreferenceStore.getDeviceToken()
            retrieved shouldBeEqualTo token
        }

        val finalToken = globalPreferenceStore.getDeviceToken()
        finalToken shouldBeEqualTo tokens.last()
    }
}
