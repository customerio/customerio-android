package io.customer.datapipelines

import android.app.Activity
import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.random
import io.customer.commontest.util.ScopeProviderStub
import io.customer.datapipelines.testutils.core.DataPipelinesTestConfig
import io.customer.datapipelines.testutils.core.IntegrationTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.datapipelines.testutils.extensions.deviceToken
import io.customer.datapipelines.testutils.extensions.encodeToJsonValue
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.trackEvents
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.ScopeProvider
import io.customer.sdk.data.store.DeviceStore
import io.customer.sdk.data.store.GlobalPreferenceStore
import io.customer.sdk.util.EventNames
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldHaveSize
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushEnabledRefreshTests : IntegrationTest() {
    private lateinit var globalPreferenceStore: GlobalPreferenceStore
    private lateinit var deviceStore: DeviceStore
    private lateinit var outputReaderPlugin: OutputReaderPlugin

    private var isPushEnabled = true

    override fun setup(testConfig: TestConfig) {
        // Each test sets up the SDK with its own configuration
    }

    private fun setupWithConfig(testConfig: DataPipelinesTestConfig = testConfiguration {}) {
        super.setup(
            testConfiguration {
                diGraph {
                    sdk { overrideDependency<ScopeProvider>(ScopeProviderStub.Unconfined()) }
                }
            } + testConfig
        )

        val androidSDKComponent = SDKComponent.android()
        globalPreferenceStore = androidSDKComponent.globalPreferenceStore
        deviceStore = androidSDKComponent.deviceStore
        every { deviceStore.isPushEnabled } answers { isPushEnabled }

        outputReaderPlugin = OutputReaderPlugin()
        analytics.add(outputReaderPlugin)
    }

    private val deviceUpdateEvents
        get() = outputReaderPlugin.trackEvents.filter { it.event == EventNames.DEVICE_UPDATE }

    private fun registerDeviceToken(token: String = String.random) {
        every { globalPreferenceStore.getDeviceToken() } returns token
        sdkInstance.registerDeviceToken(token)
    }

    private fun resumeActivity() {
        SDKComponent.activityLifecycleCallbacks.onActivityResumed(mockk<Activity>(relaxed = true))
    }

    @Test
    fun resume_givenPushEnabledUnchangedSinceRegistration_expectNoDeviceUpdate() {
        setupWithConfig()
        registerDeviceToken()

        resumeActivity()

        deviceUpdateEvents shouldHaveSize 1
    }

    @Test
    fun resume_givenPushEnabledChangedSinceRegistration_expectDeviceUpdateWithNewValue() {
        setupWithConfig()
        val givenToken = String.random
        registerDeviceToken(givenToken)

        isPushEnabled = false
        resumeActivity()

        deviceUpdateEvents shouldHaveSize 2
        val refreshEvent = deviceUpdateEvents.last()
        refreshEvent.context.deviceToken shouldBeEqualTo givenToken
        refreshEvent.properties shouldContain ("push_enabled" to false).encodeToJsonValue()
    }

    @Test
    fun resume_givenChangeAlreadySent_expectNoRepeatDeviceUpdate() {
        setupWithConfig()
        registerDeviceToken()

        isPushEnabled = false
        resumeActivity()
        resumeActivity()

        deviceUpdateEvents shouldHaveSize 2
    }

    @Test
    fun resume_givenPushEnabledChangedBack_expectDeviceUpdateForEachChange() {
        setupWithConfig()
        registerDeviceToken()

        isPushEnabled = false
        resumeActivity()
        isPushEnabled = true
        resumeActivity()

        deviceUpdateEvents shouldHaveSize 3
        deviceUpdateEvents.last().properties shouldContain ("push_enabled" to true).encodeToJsonValue()
    }

    @Test
    fun resume_givenPermissionReadFailedOnPreviousResume_expectNextResumeStillSendsChange() {
        setupWithConfig()
        registerDeviceToken()

        every { deviceStore.isPushEnabled } throws IllegalStateException("binder failure")
        resumeActivity()
        deviceUpdateEvents shouldHaveSize 1

        isPushEnabled = false
        every { deviceStore.isPushEnabled } answers { isPushEnabled }
        resumeActivity()
        deviceUpdateEvents shouldHaveSize 2
        deviceUpdateEvents.last().properties shouldContain ("push_enabled" to false).encodeToJsonValue()
    }

    @Test
    fun resume_givenNoDeviceToken_expectNoPermissionReadOrDeviceUpdate() {
        setupWithConfig()

        isPushEnabled = false
        resumeActivity()

        verify(exactly = 0) { deviceStore.isPushEnabled }
        deviceUpdateEvents shouldHaveSize 0
    }

    @Test
    fun resume_givenStoredTokenAndNothingSentThisProcess_expectFirstResumeOnlyRecordsValue() {
        setupWithConfig()
        every { globalPreferenceStore.getDeviceToken() } returns String.random

        resumeActivity()
        deviceUpdateEvents shouldHaveSize 0

        isPushEnabled = false
        resumeActivity()
        deviceUpdateEvents shouldHaveSize 1
        deviceUpdateEvents.last().properties shouldContain ("push_enabled" to false).encodeToJsonValue()
    }

    @Test
    fun resume_givenAutoTrackDeviceAttributesDisabled_expectNoDeviceUpdate() {
        setupWithConfig(
            testConfiguration {
                sdkConfig { autoTrackDeviceAttributes(false) }
            }
        )
        registerDeviceToken()

        resumeActivity()
        isPushEnabled = false
        resumeActivity()

        deviceUpdateEvents shouldHaveSize 1
    }

    @Test
    fun activityStart_givenPushEnabledChanged_expectNoDeviceUpdateUntilResume() {
        setupWithConfig()
        registerDeviceToken()

        isPushEnabled = false
        SDKComponent.activityLifecycleCallbacks.onActivityStarted(mockk<Activity>(relaxed = true))
        deviceUpdateEvents shouldHaveSize 1

        resumeActivity()
        deviceUpdateEvents shouldHaveSize 2
    }

    @Test
    fun resume_givenChangeSentByOtherDeviceUpdate_expectNoDeviceUpdateOnResume() {
        setupWithConfig()
        registerDeviceToken()

        isPushEnabled = false
        sdkInstance.setDeviceAttributes(mapOf("custom" to "value"))
        resumeActivity()

        deviceUpdateEvents shouldHaveSize 2
        deviceUpdateEvents.last().properties shouldContain ("push_enabled" to false).encodeToJsonValue()
    }

    @Test
    fun resume_givenChangeSentByIdentify_expectNoDeviceUpdateOnResume() {
        setupWithConfig()
        registerDeviceToken()

        isPushEnabled = false
        // First identify with a registered token sends the device for the new profile
        sdkInstance.identify(String.random)
        resumeActivity()

        deviceUpdateEvents shouldHaveSize 2
        deviceUpdateEvents.last().properties shouldContain ("push_enabled" to false).encodeToJsonValue()
    }

    @Test
    fun resume_givenDeviceDeletedByClearIdentify_expectNoDeviceUpdate() {
        setupWithConfig()
        registerDeviceToken()
        sdkInstance.identify(String.random)
        sdkInstance.clearIdentify()

        isPushEnabled = false
        resumeActivity()

        deviceUpdateEvents shouldHaveSize 2
        outputReaderPlugin.trackEvents.last().event shouldBeEqualTo EventNames.DEVICE_DELETE
    }

    @Test
    fun resume_givenDeviceTokenDeleted_expectNoDeviceUpdate() {
        setupWithConfig()
        registerDeviceToken()
        sdkInstance.deleteDeviceToken()

        isPushEnabled = false
        resumeActivity()

        deviceUpdateEvents shouldHaveSize 1
        outputReaderPlugin.trackEvents.last().event shouldBeEqualTo EventNames.DEVICE_DELETE
    }

    @Test
    fun resume_givenDeviceRegisteredAgainAfterDelete_expectChangeSent() {
        setupWithConfig()
        val givenToken = String.random
        registerDeviceToken(givenToken)
        sdkInstance.deleteDeviceToken()
        registerDeviceToken(givenToken)

        isPushEnabled = false
        resumeActivity()

        deviceUpdateEvents shouldHaveSize 3
        deviceUpdateEvents.last().properties shouldContain ("push_enabled" to false).encodeToJsonValue()
    }

    @Test
    fun resume_givenProfileSwitched_expectChangeSent() {
        setupWithConfig()
        registerDeviceToken()
        sdkInstance.identify(String.random)
        // Deletes the device from the first profile, then sends it for the second
        sdkInstance.identify(String.random)

        isPushEnabled = false
        resumeActivity()

        deviceUpdateEvents shouldHaveSize 4
        deviceUpdateEvents.last().properties shouldContain ("push_enabled" to false).encodeToJsonValue()
    }
}
