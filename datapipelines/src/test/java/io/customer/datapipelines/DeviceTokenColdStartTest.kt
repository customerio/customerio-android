package io.customer.datapipelines

import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.flushCoroutines
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.datapipelines.testutils.extensions.deviceToken
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.trackEvents
import io.customer.sdk.data.model.DeviceTokenType
import io.customer.sdk.data.store.GlobalPreferenceStore
import io.customer.sdk.util.EventNames
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.junit.jupiter.api.Test

/**
 * A device token stored by an earlier launch, with nothing registered yet in this process.
 */
class DeviceTokenColdStartTest : JUnitTest(dispatcher = StandardTestDispatcher()) {

    private val testScope get() = delegate.testScope

    private val mockGlobalPreferenceStore = mockk<GlobalPreferenceStore>(relaxUnitFun = true).also {
        every { it.getDeviceToken() } returns STORED_TOKEN
        every { it.getDeviceTokenType() } returns DeviceTokenType.TOKEN
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

    // Identity restored from an earlier launch, without CustomerIO.identify() registering the device.
    private fun givenIdentifiedInEarlierLaunch(userId: String) {
        analytics.identify(userId)
        flushCoroutines(testScope)
    }

    @Test
    fun registerDeviceToken_givenDifferentToken_expectStoredDeviceDeletedFirst() = runTest {
        sdkInstance.registerDeviceToken("new-token").flushCoroutines(testScope)

        val trackedEvents = outputReaderPlugin.trackEvents shouldHaveSize 2
        trackedEvents[0].event shouldBeEqualTo EventNames.DEVICE_DELETE
        trackedEvents[0].context.deviceToken shouldBeEqualTo STORED_TOKEN
        trackedEvents[1].event shouldBeEqualTo EventNames.DEVICE_UPDATE
        trackedEvents[1].context.deviceToken shouldBeEqualTo "new-token"
    }

    @Test
    fun registerDeviceToken_givenStoredToken_expectNoDelete() = runTest {
        sdkInstance.registerDeviceToken(STORED_TOKEN).flushCoroutines(testScope)

        val trackedEvents = outputReaderPlugin.trackEvents shouldHaveSize 1
        trackedEvents[0].event shouldBeEqualTo EventNames.DEVICE_UPDATE
    }

    @Test
    fun clearIdentify_expectDeviceDeletedFromProfile() = runTest {
        givenIdentifiedInEarlierLaunch("old-profile")
        val anonymousIdBeforeClear = sdkInstance.anonymousId

        sdkInstance.clearIdentify().flushCoroutines(testScope)

        val trackedEvents = outputReaderPlugin.trackEvents shouldHaveSize 1
        trackedEvents[0].event shouldBeEqualTo EventNames.DEVICE_DELETE
        trackedEvents[0].userId shouldBeEqualTo "old-profile"
        trackedEvents[0].anonymousId shouldBeEqualTo anonymousIdBeforeClear
        trackedEvents[0].context.deviceToken shouldBeEqualTo STORED_TOKEN
    }

    @Test
    fun clearIdentify_givenNoProfile_expectDeleteWithoutUserId() = runTest {
        val anonymousIdBeforeClear = sdkInstance.anonymousId

        sdkInstance.clearIdentify().flushCoroutines(testScope)

        val trackedEvents = outputReaderPlugin.trackEvents shouldHaveSize 1
        trackedEvents[0].event shouldBeEqualTo EventNames.DEVICE_DELETE
        trackedEvents[0].userId shouldBeEqualTo ""
        trackedEvents[0].anonymousId shouldBeEqualTo anonymousIdBeforeClear
    }

    @Test
    fun deleteDeviceToken_expectStoredDeviceDeleted() = runTest {
        sdkInstance.deleteDeviceToken().flushCoroutines(testScope)

        val trackedEvents = outputReaderPlugin.trackEvents shouldHaveSize 1
        trackedEvents[0].event shouldBeEqualTo EventNames.DEVICE_DELETE
        trackedEvents[0].context.deviceToken shouldBeEqualTo STORED_TOKEN
    }

    @Test
    fun identify_givenDifferentProfile_expectDeviceMovedFromOldProfile() = runTest {
        givenIdentifiedInEarlierLaunch("old-profile")

        sdkInstance.identify("new-profile").flushCoroutines(testScope)

        val trackedEvents = outputReaderPlugin.trackEvents shouldHaveSize 2
        trackedEvents[0].event shouldBeEqualTo EventNames.DEVICE_DELETE
        trackedEvents[0].userId shouldBeEqualTo "old-profile"
        trackedEvents[0].context.deviceToken shouldBeEqualTo STORED_TOKEN
        trackedEvents[1].event shouldBeEqualTo EventNames.DEVICE_UPDATE
        trackedEvents[1].userId shouldBeEqualTo "new-profile"
        trackedEvents[1].context.deviceToken shouldBeEqualTo STORED_TOKEN
    }

    @Test
    fun identify_givenNewTokenBeforeEventsProcessed_expectOldProfileDeleteKeepsItsToken() = runTest {
        givenIdentifiedInEarlierLaunch("old-profile")

        sdkInstance.identify("new-profile")
        sdkInstance.registerDeviceToken("new-token")
        flushCoroutines(testScope)

        val oldProfileDelete = outputReaderPlugin.trackEvents.single {
            it.event == EventNames.DEVICE_DELETE && it.userId == "old-profile"
        }
        oldProfileDelete.context.deviceToken shouldBeEqualTo STORED_TOKEN
    }

    @Test
    fun track_beforeTokenRegistered_expectStoredTokenInContext() = runTest {
        sdkInstance.track("event").flushCoroutines(testScope)

        val trackedEvents = outputReaderPlugin.trackEvents shouldHaveSize 1
        trackedEvents[0].context.deviceToken shouldBeEqualTo STORED_TOKEN
    }

    private companion object {
        const val STORED_TOKEN = "stored-token"
    }
}
