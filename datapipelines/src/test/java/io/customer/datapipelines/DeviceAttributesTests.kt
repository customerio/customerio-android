package io.customer.datapipelines

import android.Manifest
import android.content.pm.PackageManager
import com.segment.analytics.kotlin.android.plugins.AndroidContextPlugin
import io.customer.datapipelines.support.core.RobolectricTest
import io.customer.datapipelines.support.core.TestConfiguration
import io.customer.datapipelines.support.core.testConfiguration
import io.customer.datapipelines.support.extensions.deviceToken
import io.customer.datapipelines.support.extensions.encodeToJsonValue
import io.customer.datapipelines.support.utils.OutputReaderPlugin
import io.customer.datapipelines.support.utils.TestConstants
import io.customer.datapipelines.support.utils.trackEvents
import io.customer.sdk.extensions.random
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DeviceAttributesTests : RobolectricTest() {
    //region Setup test environment

    private lateinit var outputReaderPlugin: OutputReaderPlugin

    init {
        // Return permission granted for network state permission so analytics can track network state
        every { mockApplication.checkCallingOrSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) } returns PackageManager.PERMISSION_GRANTED
    }

    @Before
    override fun setup() {
        // Keep setup empty to avoid calling super.setup() as it will initialize the SDK
        // and we want to test the SDK with different configurations in each test
    }

    override fun setupTestEnvironment(testConfig: TestConfiguration) {
        super.setupTestEnvironment(testConfig)

        outputReaderPlugin = OutputReaderPlugin()
        analytics.add(outputReaderPlugin)
    }

    @Test
    fun track_givenAutoTrackDeviceAttributesDisabled_expectNoDeviceAttributesInProperties() {
        setupTestEnvironment(
            testConfiguration {
                sdkConfig {
                    // Disable auto tracking of device attributes
                    setAutoTrackDeviceAttributes(false)
                }
                configurePlugins {
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
        deviceRegisterEvent.event shouldBeEqualTo TestConstants.Events.DEVICE_CREATED
        deviceRegisterEvent.context.deviceToken shouldBeEqualTo givenToken
        deviceRegisterEvent.properties.shouldBeEmpty()
    }

    @Test
    fun track_givenAutoTrackDeviceAttributesEnabled_expectDeviceAttributesInContextNotProperties() {
        setupTestEnvironment(
            testConfiguration {
                sdkConfig {
                    setAutoTrackDeviceAttributes(true)
                }
                configurePlugins {
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
        deviceRegisterEvent.event shouldBeEqualTo TestConstants.Events.DEVICE_CREATED
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
}
