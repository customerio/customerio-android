// Contract: cio-sdk-tools `verify push` reads the `sdk.state` line by its key tail (its
// docs/verify-contract.md). Renaming the `ev` or any key, or changing a value's meaning, breaks it.
package io.customer.datapipelines

import io.customer.commontest.config.TestConfig
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.sdk.Version
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.core.util.LogTail
import io.customer.sdk.core.util.Logger
import io.mockk.every
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.junit.jupiter.api.Test

/**
 * Drives each trigger through the real logger implementation and asserts the latest `sdk.state`
 * line, which is the one the tool keeps.
 */
class SdkStateLogContractTest : JUnitTest() {
    private val givenCdpApiKey = "1a2b3c4d5e6f7a8b9c0d"
    private val lines = mutableListOf<String>()

    // The mocked store does not persist, so keep the token the SDK stores
    private var storedToken: String? = null

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfiguration {
                cdpApiKey(givenCdpApiKey)
                sdkConfig { logLevel(CioLogLevel.DEBUG) }
                diGraph {
                    sdk {
                        // The real logger implementation (UnitTest swaps in UnitTestLogger), so each
                        // asserted line is exactly what reaches logcat or a wrapper's dispatcher
                        overrides.remove(dependencyKey<Logger>(null))
                        logger.setLogDispatcher { _, line -> lines.add(line) }
                    }
                    android {
                        val store = globalPreferenceStore
                        every { store.saveDeviceToken(any()) } answers { storedToken = firstArg() }
                        every { store.getDeviceToken() } answers { storedToken }
                    }
                }
            }
        )
    }

    @Test
    fun initialize_expectStateWithVersionAnonymousUserNoTokenAndKeyPrefix() {
        val line = stateLines().single()

        line shouldBeEqualTo "[Init] SDK state || ev=sdk.state version=${Version.version} user=anonymous token=none key=1a2b3c4d"
        line.contains(givenCdpApiKey).shouldBeFalse()
    }

    @Test
    fun identify_expectStateWithIdentifiedUser() {
        sdkInstance.identify("u-42")

        latestState() shouldBeEqualTo stateFields(user = "u-42", token = "none")
    }

    @Test
    fun clearIdentify_expectStateWithAnonymousUser() {
        sdkInstance.identify("u-42")

        sdkInstance.clearIdentify()

        latestState() shouldBeEqualTo stateFields(user = "anonymous", token = "none")
    }

    @Test
    fun registerDeviceToken_expectStateWithFullToken() {
        sdkInstance.identify("u-42")

        sdkInstance.registerDeviceToken("fcm-token:APA91b=")

        latestState() shouldBeEqualTo stateFields(user = "u-42", token = "fcm-token:APA91b=")
    }

    @Test
    fun deleteDeviceToken_expectNewStateLine() {
        sdkInstance.registerDeviceToken("fcm-token")
        val linesBeforeDelete = stateLines().size

        sdkInstance.deleteDeviceToken()

        stateLines().size shouldBeEqualTo linesBeforeDelete + 1
        // Deleting removes the device from the profile; the SDK keeps the token it stored
        latestState() shouldBeEqualTo stateFields(user = "anonymous", token = "fcm-token")
    }

    private fun stateLines(): List<String> = lines.filter { it.substringAfterLast(LogTail.DELIMITER).startsWith("ev=sdk.state ") }

    // The latest line's pairs, split the way the tool splits them; no value here needs decoding
    private fun latestState(): List<Pair<String, String>> =
        stateLines().last().substringAfterLast(LogTail.DELIMITER).split(' ').map { pair ->
            pair.substringBefore('=') to pair.substringAfter('=')
        }

    private fun stateFields(user: String, token: String) = listOf(
        "ev" to "sdk.state",
        "version" to Version.version,
        "user" to user,
        "token" to token,
        "key" to "1a2b3c4d"
    )
}
