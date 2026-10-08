package io.customer.datapipelines

import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.flushCoroutines
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.datapipelines.testutils.extensions.deviceToken
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.trackEvents
import io.customer.sdk.core.di.SDKComponent
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
 * A token registered on another thread while setDeviceAttributes runs must not be undone by it.
 */
class DeviceTokenRefreshRaceTest : JUnitTest(dispatcher = StandardTestDispatcher()) {

    private val testScope get() = delegate.testScope

    @Volatile
    private var storedToken: String? = "token-a"

    private val mockGlobalPreferenceStore = mockk<GlobalPreferenceStore>(relaxUnitFun = true).also {
        every { it.getDeviceToken() } answers { storedToken }
        every { it.getDeviceTokenType() } returns null
        every { it.saveDeviceToken(any(), any()) } answers { storedToken = firstArg() }
    }
    private lateinit var outputReaderPlugin: OutputReaderPlugin

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfiguration {
                sdkConfig { autoAddCustomerIODestination(true) }
                diGraph {
                    android { overrideDependency<GlobalPreferenceStore>(mockGlobalPreferenceStore) }
                }
            }
        )

        outputReaderPlugin = OutputReaderPlugin()
        analytics.add(outputReaderPlugin)

        @Suppress("OPT_IN_USAGE")
        testScope.runCurrent()
    }

    @Test
    fun setDeviceAttributes_givenTokenRegisteredOnAnotherThreadMeanwhile_expectNewTokenRegisteredLast() = runTest {
        val registration = Thread { sdkInstance.registerDeviceToken("token-b") }
        // Starts the other thread once setDeviceAttributes has read token-a, and gives it time to
        // finish if nothing holds it back.
        every { SDKComponent.android().deviceStore.buildDeviceAttributes() } answers {
            if (registration.state == Thread.State.NEW) {
                registration.start()
                registration.join(500)
            }
            emptyMap()
        }

        sdkInstance.setDeviceAttributes(mapOf("key" to "value"))
        registration.join()
        flushCoroutines(testScope)

        val deviceEvents = outputReaderPlugin.trackEvents.map { it.event to it.context.deviceToken }
        deviceEvents shouldBeEqualTo listOf(
            EventNames.DEVICE_UPDATE to "token-a",
            EventNames.DEVICE_DELETE to "token-a",
            EventNames.DEVICE_UPDATE to "token-b"
        )
    }
}
