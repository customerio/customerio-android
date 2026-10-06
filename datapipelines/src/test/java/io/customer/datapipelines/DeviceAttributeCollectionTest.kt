package io.customer.datapipelines

import io.customer.commontest.config.TestConfig
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.datapipelines.testutils.extensions.deviceToken
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.trackEvents
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.store.DeviceStore
import io.customer.sdk.data.store.GlobalPreferenceStore
import io.customer.sdk.util.EventNames
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.jsonPrimitive
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSingleItem
import org.amshove.kluent.shouldNotBeEqualTo
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceAttributeCollectionTest : JUnitTest(dispatcher = StandardTestDispatcher()) {
    private val testScope get() = delegate.testScope
    private lateinit var deviceStore: DeviceStore
    private lateinit var outputReader: OutputReaderPlugin
    private var storedToken: String? = null

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfiguration {
                sdkConfig {
                    autoAddCustomerIODestination(true)
                    autoTrackDeviceAttributes(true)
                }
            }
        )

        val androidComponent = SDKComponent.android()
        val preferenceStore: GlobalPreferenceStore = androidComponent.globalPreferenceStore
        every { preferenceStore.saveDeviceToken(any()) } answers { storedToken = firstArg() }
        every { preferenceStore.getDeviceToken() } answers { storedToken }
        deviceStore = androidComponent.deviceStore
        every { deviceStore.buildDeviceAttributes() } returns mapOf("app_version" to "2.0", "push_enabled" to true)

        outputReader = OutputReaderPlugin()
        analytics.add(outputReader)
        testScope.runCurrent()
    }

    @Test
    fun testRegisterDeviceToken_whenAnalyticsIsDelayed_thenStoresTokenBeforeCollectingAttributes() {
        sdkInstance.identify("alice")
        testScope.runCurrent()

        sdkInstance.registerDeviceToken("token-a")

        sdkInstance.registeredDeviceToken shouldBeEqualTo "token-a"
        sdkInstance.isUserIdentified shouldBeEqualTo true
        verify(exactly = 0) { deviceStore.buildDeviceAttributes() }

        testScope.runCurrent()

        val event = outputReader.trackEvents.shouldHaveSingleItem()
        event.event shouldBeEqualTo EventNames.DEVICE_UPDATE
        event.userId shouldBeEqualTo "alice"
        event.context.deviceToken shouldBeEqualTo "token-a"
        event.properties.getValue("app_version").jsonPrimitive.content shouldBeEqualTo "2.0"
        event.properties.getValue("push_enabled").jsonPrimitive.content shouldBeEqualTo "true"
        verify(exactly = 1) { deviceStore.buildDeviceAttributes() }
    }

    @Test
    fun testRegisterDeviceToken_whenTokenRotatesBeforeProcessing_thenKeepsEachEventsToken() {
        sdkInstance.identify("alice")
        testScope.runCurrent()

        sdkInstance.registerDeviceToken("token-a")
        sdkInstance.registerDeviceToken("token-b")

        sdkInstance.registeredDeviceToken shouldBeEqualTo "token-b"
        testScope.runCurrent()

        outputReader.trackEvents.map { it.event to it.context.deviceToken } shouldBeEqualTo listOf(
            EventNames.DEVICE_UPDATE to "token-a",
            EventNames.DEVICE_DELETE to "token-a",
            EventNames.DEVICE_UPDATE to "token-b"
        )
        outputReader.trackEvents.map { it.userId } shouldBeEqualTo listOf("alice", "alice", "alice")
    }

    @Test
    fun testIdentify_whenProfileChangesAndResetsBeforeProcessing_thenKeepsEachEventsProfile() {
        sdkInstance.identify("alice")
        sdkInstance.registerDeviceToken("token-a")
        sdkInstance.identify("bob")
        sdkInstance.clearIdentify()
        sdkInstance.isUserIdentified shouldBeEqualTo false
        sdkInstance.identify("carol")
        sdkInstance.isUserIdentified shouldBeEqualTo true

        testScope.runCurrent()

        outputReader.trackEvents.map { it.event to it.userId } shouldBeEqualTo listOf(
            EventNames.DEVICE_UPDATE to "alice",
            EventNames.DEVICE_DELETE to "alice",
            EventNames.DEVICE_UPDATE to "bob",
            EventNames.DEVICE_DELETE to "bob",
            EventNames.DEVICE_UPDATE to "carol"
        )
        outputReader.trackEvents.map { it.context.deviceToken } shouldBeEqualTo List(5) { "token-a" }
    }

    @Test
    fun testRegisterDeviceToken_whenAttributeCollectionFails_thenStillEmitsDeviceUpdate() {
        every { deviceStore.buildDeviceAttributes() } throws IllegalStateException("system service unavailable")

        sdkInstance.registerDeviceToken("token-a")
        sdkInstance.setDeviceAttributes(mapOf("custom" to "value"))
        testScope.runCurrent()

        outputReader.trackEvents.map { it.event to it.context.deviceToken } shouldBeEqualTo listOf(
            EventNames.DEVICE_UPDATE to "token-a",
            EventNames.DEVICE_UPDATE to "token-a"
        )
        outputReader.trackEvents.last().properties.getValue("custom").jsonPrimitive.content shouldBeEqualTo "value"
    }

    @Test
    fun testRegisterDeviceToken_whenAnonymousProfileResetsBeforeProcessing_thenKeepsEachEventsAnonymousId() {
        val originalAnonymousId = sdkInstance.anonymousId
        sdkInstance.registerDeviceToken("token-a")
        sdkInstance.clearIdentify()
        val newAnonymousId = sdkInstance.anonymousId
        newAnonymousId shouldNotBeEqualTo originalAnonymousId
        sdkInstance.registerDeviceToken("token-b")

        testScope.runCurrent()

        val updates = outputReader.trackEvents.filter { it.event == EventNames.DEVICE_UPDATE }
        updates.map { it.userId } shouldBeEqualTo listOf("", "")
        updates.map { it.anonymousId } shouldBeEqualTo listOf(originalAnonymousId, newAnonymousId)
        updates.map { it.context.deviceToken } shouldBeEqualTo listOf("token-a", "token-b")
    }
}
