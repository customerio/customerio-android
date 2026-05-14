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
import io.customer.datapipelines.testutils.extensions.installationId
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.trackEvents
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.store.GlobalPreferenceStore
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import java.util.UUID
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldHaveSingleItem
import org.amshove.kluent.shouldNotBeNull
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

    private fun setupWithInstallationId(installationId: String?): DataPipelinesTestConfig {
        return testConfiguration {
            diGraph {
                android {
                    // Stub before CustomerIO init so the init-block resolution reads this value.
                    every { globalPreferenceStore.getInstallationId() } returns installationId
                }
            }
        }
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

    @Test
    fun process_givenInstallationIdExists_expectAttachedToDeviceContext() {
        val givenInstallationId = UUID.randomUUID().toString()
        setupWithConfig(setupWithInstallationId(givenInstallationId))

        val givenEventName = String.random
        analytics.process(TrackEvent(emptyJsonObject, givenEventName))

        val result = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        result.event shouldBeEqualTo givenEventName
        result.context.installationId shouldBeEqualTo givenInstallationId
    }

    @Test
    fun process_givenNoInstallationIdStored_expectGeneratedAndPersisted() {
        val savedIdSlot = slot<String>()
        setupWithConfig(
            testConfiguration {
                diGraph {
                    android {
                        every { globalPreferenceStore.getInstallationId() } returns null
                        every { globalPreferenceStore.saveInstallationId(capture(savedIdSlot)) } returns Unit
                    }
                }
            }
        )

        verify(exactly = 1) { globalPreferenceStore.saveInstallationId(any()) }
        val persistedId = savedIdSlot.captured
        // Ensure it parses as a valid UUID
        UUID.fromString(persistedId).shouldNotBeNull()

        val firstEventName = String.random
        analytics.process(TrackEvent(emptyJsonObject, firstEventName))
        val secondEventName = String.random
        analytics.process(TrackEvent(emptyJsonObject, secondEventName))

        val events = outputReaderPlugin.trackEvents
        events[0].context.installationId shouldBeEqualTo persistedId
        events[1].context.installationId shouldBeEqualTo persistedId
    }

    @Test
    fun process_acrossClearIdentify_expectInstallationIdUnchanged() {
        val givenInstallationId = UUID.randomUUID().toString()
        setupWithConfig(setupWithInstallationId(givenInstallationId))

        val firstEventName = String.random
        analytics.process(TrackEvent(emptyJsonObject, firstEventName))
        val installationIdBefore = outputReaderPlugin.trackEvents.last().context.installationId
        outputReaderPlugin.reset()

        sdkInstance.clearIdentify()

        val secondEventName = String.random
        analytics.process(TrackEvent(emptyJsonObject, secondEventName))
        val installationIdAfter = outputReaderPlugin.trackEvents.last().context.installationId

        installationIdBefore shouldBeEqualTo givenInstallationId
        installationIdAfter shouldBeEqualTo installationIdBefore
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
