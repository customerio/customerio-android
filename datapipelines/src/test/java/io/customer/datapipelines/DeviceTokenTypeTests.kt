package io.customer.datapipelines

import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.random
import io.customer.datapipelines.testutils.core.IntegrationTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.trackEvents
import io.customer.sdk.DataPipelinesLogger
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.model.DeviceTokenType
import io.customer.sdk.data.store.GlobalPreferenceStore
import io.customer.sdk.util.EventNames
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.jsonPrimitive
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSingleItem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceTokenTypeTests : IntegrationTest() {
    private lateinit var globalPreferenceStore: GlobalPreferenceStore
    private lateinit var outputReaderPlugin: OutputReaderPlugin
    private val mockDataPipelinesLogger = mockk<DataPipelinesLogger>(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        // Each test sets up its own SDK config
    }

    private fun setupSdk(autoTrackDeviceAttributes: Boolean = true) {
        super.setup(
            testConfiguration {
                sdkConfig { autoTrackDeviceAttributes(autoTrackDeviceAttributes) }
                diGraph { sdk { overrideDependency<DataPipelinesLogger>(mockDataPipelinesLogger) } }
            }
        )
        globalPreferenceStore = SDKComponent.android().globalPreferenceStore
        // Stateful so reads see what registration wrote
        var storedToken: String? = null
        var storedType: DeviceTokenType? = null
        every { globalPreferenceStore.saveDeviceToken(any(), any()) } answers {
            storedToken = firstArg()
            storedType = secondArg()
        }
        every { globalPreferenceStore.getDeviceToken() } answers { storedToken }
        every { globalPreferenceStore.getDeviceTokenType() } answers { storedType }

        outputReaderPlugin = OutputReaderPlugin()
        analytics.add(outputReaderPlugin)
    }

    private fun deviceUpdateTokenType(): String? {
        val event = outputReaderPlugin.trackEvents.filter { it.event == EventNames.DEVICE_UPDATE }.shouldHaveSingleItem()
        return event.properties[TOKEN_TYPE]?.jsonPrimitive?.content
    }

    @Test
    fun registerDeviceToken_givenSdkFetchedFid_expectTypeStoredAndSent() {
        setupSdk()
        val fid = String.random

        sdkInstance.registerDeviceToken(fid, DeviceTokenType.FID)

        assertCalledOnce { globalPreferenceStore.saveDeviceToken(fid, DeviceTokenType.FID) }
        deviceUpdateTokenType() shouldBeEqualTo "fid"
    }

    @Test
    fun registerDeviceToken_givenCustomerToken_expectNoType() {
        setupSdk()
        val token = String.random

        sdkInstance.registerDeviceToken(token)

        assertCalledOnce { globalPreferenceStore.saveDeviceToken(token, null) }
        deviceUpdateTokenType() shouldBeEqualTo null
    }

    @Test
    fun registerDeviceToken_givenAutoTrackDeviceAttributesDisabled_expectTypeStillSent() {
        setupSdk(autoTrackDeviceAttributes = false)

        sdkInstance.registerDeviceToken(String.random, DeviceTokenType.TOKEN)

        deviceUpdateTokenType() shouldBeEqualTo "token"
    }

    @Test
    fun identify_givenTypedTokenRegistered_expectTypeSentWithProfileDevice() {
        setupSdk()
        sdkInstance.registerDeviceToken(String.random, DeviceTokenType.FID)
        outputReaderPlugin.reset()

        sdkInstance.identify(String.random)

        deviceUpdateTokenType() shouldBeEqualTo "fid"
    }

    @Test
    fun setDeviceAttributes_givenReservedTypeAndKnownType_expectSdkTypeWinsAndWarning() {
        setupSdk()
        sdkInstance.registerDeviceToken(String.random, DeviceTokenType.TOKEN)
        outputReaderPlugin.reset()

        sdkInstance.setDeviceAttributes(mapOf(TOKEN_TYPE to "fid"))

        deviceUpdateTokenType() shouldBeEqualTo "token"
        assertCalledOnce { mockDataPipelinesLogger.logReservedDeviceTokenTypeIgnored() }
    }

    @Test
    fun setDeviceAttributes_givenReservedTypeAndUnknownType_expectDroppedAndWarning() {
        setupSdk()
        sdkInstance.registerDeviceToken(String.random)
        outputReaderPlugin.reset()

        sdkInstance.setDeviceAttributes(mapOf(TOKEN_TYPE to "fid"))

        deviceUpdateTokenType() shouldBeEqualTo null
        assertCalledOnce { mockDataPipelinesLogger.logReservedDeviceTokenTypeIgnored() }
    }

    @Test
    fun setDeviceAttributes_givenNoReservedType_expectNoWarning() {
        setupSdk()
        sdkInstance.registerDeviceToken(String.random, DeviceTokenType.TOKEN)

        sdkInstance.setDeviceAttributes(mapOf("color" to "blue"))

        assertCalledNever { mockDataPipelinesLogger.logReservedDeviceTokenTypeIgnored() }
    }

    private companion object {
        const val TOKEN_TYPE = "_cio_token_type"
    }
}
