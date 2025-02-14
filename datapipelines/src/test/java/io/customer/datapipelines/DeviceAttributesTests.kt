package io.customer.datapipelines

import android.Manifest
import android.content.pm.PackageManager
import com.segment.analytics.kotlin.android.plugins.AndroidContextPlugin
import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.random
import io.customer.datapipelines.testutils.core.DataPipelinesTestConfig
import io.customer.datapipelines.testutils.core.IntegrationTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.datapipelines.testutils.extensions.deviceToken
import io.customer.datapipelines.testutils.extensions.encodeToJsonValue
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.trackEvents
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.store.GlobalPreferenceStore
import io.customer.sdk.util.EventNames
import io.mockk.every
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldHaveSingleItem
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.amshove.kluent.shouldNotBeNullOrBlank
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceAttributesTests : IntegrationTest() {
    private lateinit var globalPreferenceStore: GlobalPreferenceStore
    private lateinit var outputReaderPlugin: OutputReaderPlugin

    init {
        // Return permission granted for network state permission so analytics can track network state
        every { applicationMock.checkCallingOrSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) } returns PackageManager.PERMISSION_GRANTED
    }

    override fun setup(testConfig: TestConfig) {
        // Keep setup empty to avoid calling super.setup() as it will initialize the SDK
        // and we want to test the SDK with different configurations in each test
    }

    private fun setupWithConfig(testConfig: DataPipelinesTestConfig) {
        super.setup(testConfig)

        val androidSDKComponent = SDKComponent.android()
        globalPreferenceStore = androidSDKComponent.globalPreferenceStore

        outputReaderPlugin = OutputReaderPlugin()
        analytics.add(outputReaderPlugin)
    }

    @Test
    fun track_givenAutoTrackDeviceAttributesDisabled_expectNoDeviceAttributesInProperties() {
        setupWithConfig(
            testConfiguration {
                sdkConfig {
                    // Disable auto tracking of device attributes
                    autoTrackDeviceAttributes(false)
                }
                analytics {
                    // Add Android context plugin so that device attributes can be tracked by analytics
                    add(AndroidContextPlugin())
                }
            }
        )

        val givenIdentifier = String.random
        val givenToken = String.random

        sdkInstance.identify(givenIdentifier)
        every { globalPreferenceStore.getDeviceToken() } returns givenToken
        sdkInstance.registerDeviceToken(givenToken)

        val deviceRegisterEvent = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        deviceRegisterEvent.userId shouldBeEqualTo givenIdentifier
        deviceRegisterEvent.event shouldBeEqualTo EventNames.DEVICE_UPDATE
        deviceRegisterEvent.context.deviceToken shouldBeEqualTo givenToken
        deviceRegisterEvent.properties.shouldBeEmpty()
    }

    @Test
    fun track_givenAutoTrackDeviceAttributesEnabled_expectDeviceAttributesInProperties() {
        setupWithConfig(
            testConfiguration {
                sdkConfig { autoTrackDeviceAttributes(true) }
                analytics {
                    // Add Android context plugin so that device attributes can be tracked by analytics
                    add(AndroidContextPlugin())
                }
            }
        )

        val givenIdentifier = String.random
        val givenToken = String.random

        sdkInstance.identify(givenIdentifier)
        every { globalPreferenceStore.getDeviceToken() } returns givenToken
        sdkInstance.registerDeviceToken(givenToken)

        val deviceRegisterEvent = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        deviceRegisterEvent.userId shouldBeEqualTo givenIdentifier
        deviceRegisterEvent.event shouldBeEqualTo EventNames.DEVICE_UPDATE
        deviceRegisterEvent.context.deviceToken shouldBeEqualTo givenToken

        val properties = deviceRegisterEvent.properties
        properties.keys shouldHaveSize 13
        properties shouldContain ("device_os" to 30).encodeToJsonValue()
        properties shouldContain ("device_model" to "Pixel 6").encodeToJsonValue()
        properties shouldContain ("device_manufacturer" to "Google").encodeToJsonValue()
        properties shouldContain ("app_version" to "1.0").encodeToJsonValue()
        properties shouldContain ("cio_sdk_version" to "1.0.0-alpha.6").encodeToJsonValue()
        properties shouldContain ("device_locale" to "en-US").encodeToJsonValue()
        properties shouldContain ("push_enabled" to true).encodeToJsonValue()
        properties["timezone"]?.jsonPrimitive?.content.shouldNotBeNullOrBlank()
        properties["screen_width"]?.jsonPrimitive?.intOrNull.shouldNotBeNull()
        properties["screen_height"]?.jsonPrimitive?.intOrNull.shouldNotBeNull()
        properties["network_wifi"]?.jsonPrimitive?.booleanOrNull.shouldNotBeNull()
        properties["network_cellular"]?.jsonPrimitive?.booleanOrNull.shouldNotBeNull()
        properties["network_bluetooth"]?.jsonPrimitive?.booleanOrNull.shouldNotBeNull()
    }

    @Test
    fun track_givenCustomAttributes_expectPreferCustomAttributesInProperties() {
        setupWithConfig(
            testConfiguration {
                sdkConfig { autoTrackDeviceAttributes(true) }
                analytics {
                    // Add Android context plugin so that device attributes can be tracked by analytics
                    add(AndroidContextPlugin())
                }
            }
        )

        val givenIdentifier = String.random
        val givenToken = String.random
        val givenAttributes = mapOf(
            "device_os" to "Custom OS",
            "device_model" to "Fake Device",
            "source" to "test",
            "debugMode" to true
        )

        sdkInstance.identify(givenIdentifier)
        every { globalPreferenceStore.getDeviceToken() } returns givenToken
        sdkInstance.registerDeviceToken(givenToken)
        sdkInstance.deviceAttributes = givenAttributes

        // 1. Device Created
        // 2. Device Updated
        val deviceRegisterEvent = outputReaderPlugin.trackEvents.shouldHaveSize(2).last()
        deviceRegisterEvent.userId shouldBeEqualTo givenIdentifier
        deviceRegisterEvent.event shouldBeEqualTo EventNames.DEVICE_UPDATE
        deviceRegisterEvent.context.deviceToken shouldBeEqualTo givenToken

        val properties = deviceRegisterEvent.properties
        // Auto tracked device attributes => 13
        // Custom device attributes => 4
        // Overlapping attributes => 2
        properties.keys shouldHaveSize 15
        // Verify new custom attributes are present
        properties shouldContain ("source" to "test").encodeToJsonValue()
        properties shouldContain ("debugMode" to true).encodeToJsonValue()
        // Verify custom attributes are preferred over auto tracked attributes
        properties shouldContain ("device_os" to "Custom OS").encodeToJsonValue()
        properties shouldContain ("device_model" to "Fake Device").encodeToJsonValue()
        // Verify remaining auto tracked attributes are present
        properties shouldContain ("device_manufacturer" to "Google").encodeToJsonValue()
        properties shouldContain ("app_version" to "1.0").encodeToJsonValue()
        properties shouldContain ("cio_sdk_version" to "1.0.0-alpha.6").encodeToJsonValue()
        properties shouldContain ("device_locale" to "en-US").encodeToJsonValue()
        properties shouldContain ("push_enabled" to true).encodeToJsonValue()
        properties["timezone"]?.jsonPrimitive?.content.shouldNotBeNullOrBlank()
        properties["screen_width"]?.jsonPrimitive?.intOrNull.shouldNotBeNull()
        properties["screen_height"]?.jsonPrimitive?.intOrNull.shouldNotBeNull()
        properties["network_wifi"]?.jsonPrimitive?.booleanOrNull.shouldNotBeNull()
        properties["network_cellular"]?.jsonPrimitive?.booleanOrNull.shouldNotBeNull()
        properties["network_bluetooth"]?.jsonPrimitive?.booleanOrNull.shouldNotBeNull()
    }
}
