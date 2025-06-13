package io.customer.datapipelines

import com.segment.analytics.kotlin.core.EventType
import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.flushCoroutines
import io.customer.commontest.extensions.random
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.datapipelines.testutils.extensions.deviceToken
import io.customer.datapipelines.testutils.extensions.shouldMatchTo
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.identifyEvents
import io.customer.datapipelines.testutils.utils.trackEvents
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.store.DeviceStore
import io.customer.sdk.data.store.GlobalPreferenceStore
import io.customer.sdk.util.EventNames
import io.mockk.coEvery
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.junit.jupiter.api.Test

/**
 * Tests DataPipelines behavior using StandardTestDispatcher to simulate realistic coroutine
 * scheduling and timing.
 */
class DataPipelinesStandardDispatcherTest : JUnitTest(dispatcher = StandardTestDispatcher()) {
    //region Setup test environment

    private val testScope get() = delegate.testScope

    private lateinit var globalPreferenceStore: GlobalPreferenceStore
    private lateinit var deviceStore: DeviceStore
    private lateinit var outputReaderPlugin: OutputReaderPlugin

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfiguration {
                sdkConfig {
                    // Enable adding destination so events are processed and stored in the storage
                    autoAddCustomerIODestination(true)
                }
            }
        )

        val androidSDKComponent = SDKComponent.android()
        globalPreferenceStore = androidSDKComponent.globalPreferenceStore
        deviceStore = androidSDKComponent.deviceStore

        outputReaderPlugin = OutputReaderPlugin()
        analytics.add(outputReaderPlugin)

        // Run all pending coroutines to ensure analytics is initialized and ready to process events
        @Suppress("OPT_IN_USAGE")
        testScope.runCurrent()
    }

    //endregion
    //region Device token

    @Test
    fun device_givenTokenRefreshed_expectDeleteAndRegisterDeviceToken() = runTest {
        val givenIdentifier = String.random
        val givenPreviousDeviceToken = "old-token"
        val givenToken = "new-token"

        sdkInstance.identify(givenIdentifier).flushCoroutines(testScope)
        sdkInstance.registerDeviceToken(givenPreviousDeviceToken).flushCoroutines(testScope)
        sdkInstance.registerDeviceToken(givenToken).flushCoroutines(testScope)

        // 1. Device register (old token)
        // 2. Device delete (old token)
        // 3. Device register (new token)
        val trackedEvents = outputReaderPlugin.trackEvents shouldHaveSize 3

        val deviceDeleteEvent = trackedEvents[trackedEvents.lastIndex - 1]
        deviceDeleteEvent.userId shouldBeEqualTo givenIdentifier
        deviceDeleteEvent.type shouldBeEqualTo EventType.Track
        deviceDeleteEvent.event shouldBeEqualTo EventNames.DEVICE_DELETE

        val deviceDeleteEventContext = deviceDeleteEvent.context
        deviceDeleteEventContext.deviceToken shouldBeEqualTo givenPreviousDeviceToken
        deviceDeleteEvent.properties.shouldBeEmpty()

        val deviceRegisterEvent = trackedEvents.last()
        deviceRegisterEvent.userId shouldBeEqualTo givenIdentifier
        deviceRegisterEvent.type shouldBeEqualTo EventType.Track
        deviceRegisterEvent.event shouldBeEqualTo EventNames.DEVICE_UPDATE

        val deviceRegisterEventContext = deviceRegisterEvent.context
        deviceRegisterEventContext.deviceToken shouldBeEqualTo givenToken
        deviceRegisterEvent.properties shouldMatchTo deviceStore.buildDeviceAttributes()
    }

    @Test
    fun device_givenProfileChanged_expectDeleteDeviceTokenForOldProfile() = runTest {
        val givenIdentifier = "new-profile"
        val givenPreviouslyIdentifiedProfile = "old-profile"
        val givenToken = String.random

        coEvery { globalPreferenceStore.getDeviceToken() } returns givenToken

        sdkInstance.identify(givenPreviouslyIdentifiedProfile).flushCoroutines(testScope)
        sdkInstance.identify(givenIdentifier).flushCoroutines(testScope)

        // 1. Identify event for the old profile
        // 2. Identify event for the new profile
        outputReaderPlugin.identifyEvents.count() shouldBeEqualTo 2
        // 1. Device update event for the old profile
        // 2. Device delete event for the old profile
        // 3. Device update event for the new profile
        val trackedEvents = outputReaderPlugin.trackEvents shouldHaveSize 3

        val deviceDeleteEvent = trackedEvents[trackedEvents.lastIndex - 1]
        deviceDeleteEvent.userId shouldBeEqualTo givenPreviouslyIdentifiedProfile
        deviceDeleteEvent.event shouldBeEqualTo EventNames.DEVICE_DELETE
        deviceDeleteEvent.context.deviceToken shouldBeEqualTo givenToken

        val deviceRegisterEvent = trackedEvents.last()
        deviceRegisterEvent.userId shouldBeEqualTo givenIdentifier
        deviceRegisterEvent.event shouldBeEqualTo EventNames.DEVICE_UPDATE
        deviceRegisterEvent.context.deviceToken shouldBeEqualTo givenToken
    }

    @Test
    fun device_givenClearIdentify_expectDeviceUnregisteredFromProfile() = runTest {
        val givenIdentifier = String.random
        val givenToken = String.random

        coEvery { globalPreferenceStore.getDeviceToken() } returns givenToken

        sdkInstance.identify(givenIdentifier).flushCoroutines(testScope)
        sdkInstance.clearIdentify().flushCoroutines(testScope)

        outputReaderPlugin.identifyEvents.count() shouldBeEqualTo 1
        // 1. Device update event
        // 2. Device delete event
        val trackedEvents = outputReaderPlugin.trackEvents shouldHaveSize 2

        val deviceRegisterEvent = trackedEvents[trackedEvents.lastIndex - 1]
        deviceRegisterEvent.userId shouldBeEqualTo givenIdentifier
        deviceRegisterEvent.event shouldBeEqualTo EventNames.DEVICE_UPDATE
        deviceRegisterEvent.context.deviceToken shouldBeEqualTo givenToken

        val deviceDeleteEvent = trackedEvents.last()
        deviceDeleteEvent.userId shouldBeEqualTo givenIdentifier
        deviceDeleteEvent.event shouldBeEqualTo EventNames.DEVICE_DELETE
        deviceDeleteEvent.context.deviceToken shouldBeEqualTo givenToken
    }

    //endregion
}
