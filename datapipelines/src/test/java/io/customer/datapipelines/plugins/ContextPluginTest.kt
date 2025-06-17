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
import io.mockk.coEvery
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
        coEvery { globalPreferenceStore.getDeviceToken() } returns givenOriginalToken
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
        coEvery { globalPreferenceStore.getDeviceToken() } returns givenOriginalToken
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

    @Test
    fun process_givenTokenSetThroughDeviceTokenManager_expectTokenInContext() {
        setupWithConfig()

        val givenToken = String.random

        deviceTokenManagerStub.setDeviceToken(givenToken)

        val givenEventName = String.random
        analytics.process(TrackEvent(emptyJsonObject, givenEventName))

        val result = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        result.event shouldBeEqualTo givenEventName
        result.context.deviceToken shouldBeEqualTo givenToken
    }

    @Test
    fun process_givenTokenClearedThroughDeviceTokenManager_expectNoTokenInContext() {
        setupWithConfig()

        val initialToken = String.random

        deviceTokenManagerStub.setDeviceToken(initialToken)
        deviceTokenManagerStub.clearDeviceToken()

        val givenEventName = String.random
        analytics.process(TrackEvent(emptyJsonObject, givenEventName))

        val result = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        result.event shouldBeEqualTo givenEventName
        result.context.deviceToken.shouldBeNull()
    }

    @Test
    fun process_givenRapidTokenChanges_expectLatestTokenInSubsequentEvents() {
        setupWithConfig()

        val token1 = String.random
        val token2 = String.random
        val token3 = String.random

        deviceTokenManagerStub.setDeviceToken(token1)
        val event1Name = String.random
        analytics.process(TrackEvent(emptyJsonObject, event1Name))

        deviceTokenManagerStub.setDeviceToken(token2)
        val event2Name = String.random
        analytics.process(TrackEvent(emptyJsonObject, event2Name))

        deviceTokenManagerStub.setDeviceToken(token3)
        val event3Name = String.random
        analytics.process(TrackEvent(emptyJsonObject, event3Name))

        val results = outputReaderPlugin.trackEvents
        results.size shouldBeEqualTo 3

        results[0].context.deviceToken shouldBeEqualTo token1
        results[1].context.deviceToken shouldBeEqualTo token2
        results[2].context.deviceToken shouldBeEqualTo token3
    }

    @Test
    fun process_givenTokenSetToEmptyString_expectEmptyStringInContext() {
        setupWithConfig()

        val emptyToken = ""

        deviceTokenManagerStub.setDeviceToken(emptyToken)

        val givenEventName = String.random
        analytics.process(TrackEvent(emptyJsonObject, givenEventName))

        val result = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        result.event shouldBeEqualTo givenEventName
        result.context.deviceToken shouldBeEqualTo emptyToken
    }

    @Test
    fun process_givenTokenReplaceOperationWithCallback_expectNewTokenInContext() {
        setupWithConfig()

        val oldToken = String.random
        val newToken = String.random
        var callbackInvoked = false
        var callbackToken: String? = null

        deviceTokenManagerStub.setDeviceToken(oldToken)

        deviceTokenManagerStub.replaceToken(newToken) { deletedToken ->
            callbackInvoked = true
            callbackToken = deletedToken
        }

        val givenEventName = String.random
        analytics.process(TrackEvent(emptyJsonObject, givenEventName))

        val result = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        result.event shouldBeEqualTo givenEventName
        result.context.deviceToken shouldBeEqualTo newToken

        callbackInvoked shouldBeEqualTo true
        callbackToken shouldBeEqualTo oldToken
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
