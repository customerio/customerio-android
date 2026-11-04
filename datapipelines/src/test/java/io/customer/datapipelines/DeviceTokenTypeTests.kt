package io.customer.datapipelines

import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.random
import io.customer.commontest.util.ScopeProviderStub
import io.customer.datapipelines.testutils.core.IntegrationTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.datapipelines.testutils.extensions.deviceToken
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.TOKEN_TYPE_ATTRIBUTE
import io.customer.datapipelines.testutils.utils.trackEvents
import io.customer.sdk.DataPipelinesLogger
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.SDKComponent.eventBus
import io.customer.sdk.core.util.ScopeProvider
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
                diGraph {
                    sdk {
                        overrideDependency<DataPipelinesLogger>(mockDataPipelinesLogger)
                        overrideDependency<ScopeProvider>(ScopeProviderStub.Unconfined())
                    }
                }
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
        return event.properties[TOKEN_TYPE_ATTRIBUTE]?.jsonPrimitive?.content
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
        outputReaderPlugin.trackEvents.single().properties.keys shouldBeEqualTo setOf(TOKEN_TYPE_ATTRIBUTE, "cio_background_push_version")
    }

    @Test
    fun backgroundReadiness_givenNoToken_expectNoUpdateUntilTokenRegistration() {
        setupSdk(autoTrackDeviceAttributes = false)

        eventBus.publish(Event.BackgroundPushReadinessChangedEvent(ready = true))
        outputReaderPlugin.trackEvents.size shouldBeEqualTo 0

        sdkInstance.registerDeviceToken(String.random, DeviceTokenType.TOKEN)
        outputReaderPlugin.trackEvents.shouldHaveSingleItem().properties["cio_background_push_version"]?.jsonPrimitive?.content shouldBeEqualTo "1"
    }

    @Test
    fun backgroundReadiness_givenTokenStoredByEarlierLaunch_expectNoExtraUpdateBeforeRegistration() {
        setupSdk(autoTrackDeviceAttributes = false)
        globalPreferenceStore.saveDeviceToken("token-from-earlier-launch", DeviceTokenType.TOKEN)

        eventBus.publish(Event.BackgroundPushReadinessChangedEvent(ready = true))
        testDispatcher.scheduler.advanceUntilIdle()
        outputReaderPlugin.trackEvents.size shouldBeEqualTo 0

        sdkInstance.registerDeviceToken("token-from-earlier-launch", DeviceTokenType.TOKEN)
        outputReaderPlugin.trackEvents.shouldHaveSingleItem().properties["cio_background_push_version"]?.jsonPrimitive?.content shouldBeEqualTo "1"
    }

    @Test
    fun backgroundReadiness_givenTokenFetchFailedWithStoredToken_expectOneUpdateWithStoredToken() {
        setupSdk(autoTrackDeviceAttributes = false)
        globalPreferenceStore.saveDeviceToken("token-from-earlier-launch", DeviceTokenType.TOKEN)

        eventBus.publish(Event.BackgroundPushReadinessChangedEvent(ready = true))
        eventBus.publish(Event.BackgroundPushReadinessChangedEvent(ready = true, reportWithStoredToken = true))
        eventBus.publish(Event.BackgroundPushReadinessChangedEvent(ready = true, reportWithStoredToken = true))
        testDispatcher.scheduler.advanceUntilIdle()

        val update = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        update.context.deviceToken shouldBeEqualTo "token-from-earlier-launch"
        update.properties["cio_background_push_version"]?.jsonPrimitive?.content shouldBeEqualTo "1"
    }

    @Test
    fun backgroundReadiness_givenUnchangedValueAlreadyReported_expectNoExtraUpdate() {
        setupSdk(autoTrackDeviceAttributes = false)
        sdkInstance.registerDeviceToken(String.random, DeviceTokenType.TOKEN)

        eventBus.publish(Event.BackgroundPushReadinessChangedEvent(ready = false))
        testDispatcher.scheduler.advanceUntilIdle()

        outputReaderPlugin.trackEvents.shouldHaveSingleItem().properties["cio_background_push_version"]?.jsonPrimitive?.content shouldBeEqualTo "0"
    }

    @Test
    fun backgroundReadiness_givenManualTokenRegistration_expectReadinessSent() {
        setupSdk()

        eventBus.publish(Event.BackgroundPushReadinessChangedEvent(ready = true))
        testDispatcher.scheduler.advanceUntilIdle()
        sdkInstance.registerDeviceToken(String.random)

        outputReaderPlugin.trackEvents.shouldHaveSingleItem().properties["cio_background_push_version"]?.jsonPrimitive?.content shouldBeEqualTo "1"
    }

    @Test
    fun backgroundReadiness_givenExistingToken_expectCurrentReadinessOnEveryUpdate() {
        setupSdk(autoTrackDeviceAttributes = false)
        sdkInstance.registerDeviceToken(String.random, DeviceTokenType.TOKEN)
        outputReaderPlugin.trackEvents.last().properties["cio_background_push_version"]?.jsonPrimitive?.content shouldBeEqualTo "0"

        eventBus.publish(Event.BackgroundPushReadinessChangedEvent(ready = true))
        testDispatcher.scheduler.advanceUntilIdle()
        outputReaderPlugin.trackEvents.last().properties["cio_background_push_version"]?.jsonPrimitive?.content shouldBeEqualTo "1"

        sdkInstance.setDeviceAttributes(mapOf("cio_background_push_version" to "0"))
        outputReaderPlugin.trackEvents.last().properties["cio_background_push_version"]?.jsonPrimitive?.content shouldBeEqualTo "1"

        eventBus.publish(Event.BackgroundPushReadinessChangedEvent(ready = false))
        testDispatcher.scheduler.advanceUntilIdle()
        outputReaderPlugin.trackEvents.last().properties["cio_background_push_version"]?.jsonPrimitive?.content shouldBeEqualTo "0"
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
    fun identify_expectTokenTypeReadUnderLock() {
        setupSdk()
        sdkInstance.registerDeviceToken(String.random, DeviceTokenType.FID)
        var typeReadUnderLock: Boolean? = null
        every { globalPreferenceStore.getDeviceTokenType() } answers {
            typeReadUnderLock = Thread.holdsLock(sdkInstance)
            DeviceTokenType.FID
        }

        sdkInstance.identify(String.random)

        typeReadUnderLock shouldBeEqualTo true
    }

    @Test
    fun setDeviceAttributes_givenReservedTypeAndKnownType_expectSdkTypeWinsAndWarning() {
        setupSdk()
        sdkInstance.registerDeviceToken(String.random, DeviceTokenType.TOKEN)
        outputReaderPlugin.reset()

        sdkInstance.setDeviceAttributes(mapOf(TOKEN_TYPE_ATTRIBUTE to "fid"))

        deviceUpdateTokenType() shouldBeEqualTo "token"
        assertCalledOnce { mockDataPipelinesLogger.logReservedDeviceTokenTypeIgnored() }
    }

    @Test
    fun setDeviceAttributes_givenReservedTypeAndUnknownType_expectDroppedAndWarning() {
        setupSdk()
        sdkInstance.registerDeviceToken(String.random)
        outputReaderPlugin.reset()

        sdkInstance.setDeviceAttributes(mapOf(TOKEN_TYPE_ATTRIBUTE to "fid"))

        deviceUpdateTokenType() shouldBeEqualTo null
        assertCalledOnce { mockDataPipelinesLogger.logReservedDeviceTokenTypeIgnored() }
    }

    @Test
    fun setDeviceAttributes_expectTokenTypeReadUnderLock() {
        setupSdk()
        sdkInstance.registerDeviceToken(String.random, DeviceTokenType.FID)
        var typeReadUnderLock: Boolean? = null
        every { globalPreferenceStore.getDeviceTokenType() } answers {
            typeReadUnderLock = Thread.holdsLock(sdkInstance)
            DeviceTokenType.FID
        }

        sdkInstance.setDeviceAttributes(mapOf(TOKEN_TYPE_ATTRIBUTE to "token"))

        typeReadUnderLock shouldBeEqualTo true
    }

    @Test
    fun setDeviceAttributes_givenNoReservedType_expectNoWarning() {
        setupSdk()
        sdkInstance.registerDeviceToken(String.random, DeviceTokenType.TOKEN)

        sdkInstance.setDeviceAttributes(mapOf("color" to "blue"))

        assertCalledNever { mockDataPipelinesLogger.logReservedDeviceTokenTypeIgnored() }
    }
}
