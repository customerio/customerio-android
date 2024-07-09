package io.customer.datapipelines.plugins

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.TrackEvent
import com.segment.analytics.kotlin.core.emptyJsonObject
import com.segment.analytics.kotlin.core.platform.Plugin
import com.segment.analytics.kotlin.core.utilities.putInContextUnderKey
import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.random
import io.customer.datapipelines.testutils.core.DataPipelinesTestConfig
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.datapipelines.testutils.extensions.deviceToken
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.trackEvents
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.store.GlobalPreferenceStore
import io.mockk.every
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldHaveSingleItem
import org.junit.jupiter.api.Test

class ContextPluginTest : JUnitTest() {
    private lateinit var globalPreferenceStore: GlobalPreferenceStore
    private lateinit var outputReaderPlugin: OutputReaderPlugin

    override fun setup(testConfig: TestConfig) {
        // Keep setup empty to avoid calling super.setup() as it will initialize the SDK
        // and we want to test the SDK with different configurations in each test
    }

    private fun setupWithConfig(testConfig: DataPipelinesTestConfig = testConfiguration {}) {
        super.setup(testConfig)

        val androidSDKComponent = SDKComponent.android()
        globalPreferenceStore = androidSDKComponent.globalPreferenceStore

        outputReaderPlugin = OutputReaderPlugin()
        analytics.add(outputReaderPlugin)
    }

    @Test
    fun process_givenTokenExists_expectDoNotUpdateContextDeviceToken() {
        val migrationTokenPlugin = MigrationTokenPlugin()
        setupWithConfig(
            testConfiguration {
                analytics { add(migrationTokenPlugin) }
            }
        )

        val givenOriginalToken = String.random
        every { globalPreferenceStore.getDeviceToken() } returns givenOriginalToken
        sdkInstance.registerDeviceToken(givenOriginalToken)
        outputReaderPlugin.reset()

        val givenEventName = String.random
        val givenNewToken = String.random
        migrationTokenPlugin.newToken = givenNewToken
        analytics.process(TrackEvent(emptyJsonObject, givenEventName))

        // To avoid false positives, ensure that original token is not null
        // and ContextPlugin was attached after MigrationTokenPlugin
        migrationTokenPlugin.originalToken.shouldBeNull()

        val result = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        result.event shouldBeEqualTo givenEventName
        result.context.deviceToken shouldBeEqualTo givenNewToken
    }

    @Test
    fun process_givenTokenDoesNotExist_expectUpdateContextDeviceToken() {
        setupWithConfig()

        val givenOriginalToken = String.random
        every { globalPreferenceStore.getDeviceToken() } returns givenOriginalToken
        sdkInstance.registerDeviceToken(givenOriginalToken)
        outputReaderPlugin.reset()

        val givenEventName = String.random
        analytics.process(TrackEvent(emptyJsonObject, givenEventName))

        val result = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        result.event shouldBeEqualTo givenEventName
        result.context.deviceToken shouldBeEqualTo givenOriginalToken
    }

    @Test
    fun process_givenNoTokenAvailable_expectNoContextDeviceToken() {
        setupWithConfig()

        val givenEventName = String.random
        analytics.process(TrackEvent(emptyJsonObject, givenEventName))

        val result = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        result.event shouldBeEqualTo givenEventName
        result.context.deviceToken.shouldBeNull()
    }
}

private class MigrationTokenPlugin : Plugin {
    override val type: Plugin.Type = Plugin.Type.Before
    override lateinit var analytics: Analytics
    var originalToken: String? = null
    var newToken: String? = null

    override fun execute(event: BaseEvent): BaseEvent? {
        originalToken = event.context.deviceToken
        newToken?.let { token ->
            event.putInContextUnderKey("device", "token", token)
        }
        return super.execute(event)
    }
}
