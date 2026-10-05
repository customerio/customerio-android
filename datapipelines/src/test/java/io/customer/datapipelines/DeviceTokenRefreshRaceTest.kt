package io.customer.datapipelines

import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.flushCoroutines
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.datapipelines.testutils.extensions.deviceToken
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.trackEvents
import io.customer.sdk.DataPipelinesLogger
import io.customer.sdk.data.store.GlobalPreferenceStore
import io.customer.sdk.util.EventNames
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

/**
 * setDeviceAttributes tracks outside the registration lock, so registrations can land while it runs.
 * Store and logger hooks inject them at fixed points to make the interleaving deterministic.
 */
class DeviceTokenRefreshRaceTest : JUnitTest(dispatcher = StandardTestDispatcher()) {

    private val testScope get() = delegate.testScope

    private var storedToken: String? = "token-a"
    private var onTokenTypeRead: (() -> Unit)? = null
    private var onTokenRefreshed: (() -> Unit)? = null

    private val mockGlobalPreferenceStore = mockk<GlobalPreferenceStore>(relaxUnitFun = true).also {
        every { it.getDeviceToken() } answers { storedToken }
        every { it.getDeviceTokenType() } answers { takeHook(onTokenTypeRead) { onTokenTypeRead = null }; null }
        every { it.saveDeviceToken(any(), any()) } answers { storedToken = firstArg() }
    }
    private val mockDataPipelinesLogger = mockk<DataPipelinesLogger>(relaxed = true).also {
        every { it.logPushTokenRefreshed() } answers { takeHook(onTokenRefreshed) { onTokenRefreshed = null } }
    }
    private lateinit var outputReaderPlugin: OutputReaderPlugin

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfiguration {
                sdkConfig { autoAddCustomerIODestination(true) }
                diGraph {
                    sdk { overrideDependency<DataPipelinesLogger>(mockDataPipelinesLogger) }
                    android { overrideDependency<GlobalPreferenceStore>(mockGlobalPreferenceStore) }
                }
            }
        )

        outputReaderPlugin = OutputReaderPlugin()
        analytics.add(outputReaderPlugin)

        @Suppress("OPT_IN_USAGE")
        testScope.runCurrent()
    }

    // Each hook fires once; it's cleared first because it can re-enter the same call.
    private fun takeHook(hook: (() -> Unit)?, clear: () -> Unit) {
        clear()
        hook?.invoke()
    }

    @Test
    fun setDeviceAttributes_givenTokenRegisteredBeforeRefreshDelete_expectDeleteKeepsTokenItRefreshed() = runTest {
        // token-b registers right after setDeviceAttributes reads token-a, so it sees a refresh from
        // token-b; token-c then registers before that refresh sends its delete.
        onTokenTypeRead = {
            sdkInstance.registerDeviceToken("token-b")
            onTokenRefreshed = { sdkInstance.registerDeviceToken("token-c") }
        }

        sdkInstance.setDeviceAttributes(mapOf("key" to "value"))
        flushCoroutines(testScope)

        val deletedTokens = outputReaderPlugin.trackEvents
            .filter { it.event == EventNames.DEVICE_DELETE }
            .map { it.context.deviceToken }
        deletedTokens shouldBeEqualTo listOf("token-a", "token-b", "token-b")
    }
}
