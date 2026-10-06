package io.customer.datapipelines

import io.customer.commontest.config.TestConfig
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.datapipelines.testutils.extensions.deviceToken
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.identifyEvents
import io.customer.datapipelines.testutils.utils.trackEvents
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Clock
import io.customer.sdk.data.store.DeviceStore
import io.customer.sdk.data.store.GlobalPreferenceStore
import io.customer.sdk.data.store.SecureUserStore
import io.customer.sdk.util.EventNames
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.jsonPrimitive
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSingleItem
import org.amshove.kluent.shouldNotBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceAttributeCollectionTest : JUnitTest(dispatcher = StandardTestDispatcher()) {
    private val testScope get() = delegate.testScope
    private lateinit var deviceStore: DeviceStore
    private lateinit var outputReader: OutputReaderPlugin
    private var storedToken: String? = null
    private val userStore = mockk<SecureUserStore>(relaxUnitFun = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfiguration {
                sdkConfig {
                    autoAddCustomerIODestination(true)
                    autoTrackDeviceAttributes(true)
                }
                diGraph {
                    android { overrideDependency<SecureUserStore>(userStore) }
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
    fun testRegisterDeviceToken_whenTokenRotatesBeforeProcessing_thenDiscardsOldUpdateAndKeepsEachEventsToken() {
        sdkInstance.identify("alice")
        testScope.runCurrent()

        sdkInstance.registerDeviceToken("token-a")
        sdkInstance.registerDeviceToken("token-b")

        sdkInstance.registeredDeviceToken shouldBeEqualTo "token-b"
        testScope.runCurrent()

        outputReader.trackEvents.map { it.event to it.context.deviceToken } shouldBeEqualTo listOf(
            EventNames.DEVICE_DELETE to "token-a",
            EventNames.DEVICE_UPDATE to "token-b"
        )
        outputReader.trackEvents.map { it.userId } shouldBeEqualTo listOf("alice", "alice")
    }

    @Test
    fun testIdentify_whenProfileChangesAndResetsBeforeProcessing_thenDiscardsOldUpdatesAndKeepsEachDeletionsProfile() {
        sdkInstance.identify("alice")
        sdkInstance.registerDeviceToken("token-a")
        sdkInstance.identify("bob")
        sdkInstance.clearIdentify()
        sdkInstance.isUserIdentified shouldBeEqualTo false
        sdkInstance.identify("carol")
        sdkInstance.isUserIdentified shouldBeEqualTo true

        testScope.runCurrent()

        outputReader.trackEvents.map { it.event to it.userId } shouldBeEqualTo listOf(
            EventNames.DEVICE_DELETE to "alice",
            EventNames.DEVICE_DELETE to "bob",
            EventNames.DEVICE_UPDATE to "carol"
        )
        outputReader.trackEvents.map { it.context.deviceToken } shouldBeEqualTo List(3) { "token-a" }
    }

    @Test
    fun testIdentify_whenProfileReturnsToSameUserBeforeProcessing_thenDoesNotReviveOldUpdates() {
        sdkInstance.identify("alice")
        sdkInstance.registerDeviceToken("token-a")
        sdkInstance.setDeviceAttributes(mapOf("obsolete" to "old-session"))
        sdkInstance.identify("bob")
        sdkInstance.identify("alice")
        sdkInstance.setDeviceAttributes(mapOf("fresh" to "new-session"))

        testScope.runCurrent()

        val updates = outputReader.trackEvents.filter { it.event == EventNames.DEVICE_UPDATE }
        updates.map { it.userId } shouldBeEqualTo listOf("alice", "alice")
        updates.map { it.context.deviceToken } shouldBeEqualTo listOf("token-a", "token-a")
        updates.any { "obsolete" in it.properties } shouldBeEqualTo false
        updates.last().properties.getValue("fresh").jsonPrimitive.content shouldBeEqualTo "new-session"
        outputReader.trackEvents.filter { it.event == EventNames.DEVICE_DELETE }.map { it.userId } shouldBeEqualTo listOf("alice", "bob")
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
    fun testRegisterDeviceToken_whenAnonymousProfileResetsBeforeProcessing_thenDiscardsOldUpdateAndKeepsNewAnonymousId() {
        val originalAnonymousId = sdkInstance.anonymousId
        sdkInstance.registerDeviceToken("token-a")
        sdkInstance.clearIdentify()
        val newAnonymousId = sdkInstance.anonymousId
        newAnonymousId shouldNotBeEqualTo originalAnonymousId
        sdkInstance.registerDeviceToken("token-b")

        testScope.runCurrent()

        val updates = outputReader.trackEvents.filter { it.event == EventNames.DEVICE_UPDATE }
        updates.map { it.userId } shouldBeEqualTo listOf("")
        updates.map { it.anonymousId } shouldBeEqualTo listOf(newAnonymousId)
        updates.map { it.context.deviceToken } shouldBeEqualTo listOf("token-b")
    }

    @Test
    fun testIdentify_whenIdentityPersistenceFails_thenAllowsRetryOfSameProfile() {
        every { userStore.saveUserId("alice") } throws IllegalStateException("identity store unavailable")
        assertThrows<IllegalStateException> { sdkInstance.identify("alice") }

        justRun { userStore.saveUserId("alice") }
        sdkInstance.identify("alice")

        verify(exactly = 2) { userStore.saveUserId("alice") }
    }

    @Test
    fun testIdentify_whenFirstIdentityPersistenceFails_thenRetryRegistersStoredToken() {
        sdkInstance.registerDeviceToken("token-a")
        testScope.runCurrent()
        every { userStore.saveUserId("alice") } throws IllegalStateException("identity store unavailable")
        assertThrows<IllegalStateException> { sdkInstance.identify("alice") }

        justRun { userStore.saveUserId("alice") }
        sdkInstance.identify("alice")
        testScope.runCurrent()

        val update = outputReader.trackEvents.filter {
            it.event == EventNames.DEVICE_UPDATE && it.userId == "alice"
        }.shouldHaveSingleItem()
        update.context.deviceToken shouldBeEqualTo "token-a"
    }

    @Test
    fun testIdentify_whenProfileChangePersistenceFails_thenRetryRegistersTokenToNewProfile() {
        sdkInstance.identify("alice")
        sdkInstance.registerDeviceToken("token-a")
        testScope.runCurrent()
        every { userStore.saveUserId("bob") } throws IllegalStateException("identity store unavailable")
        assertThrows<IllegalStateException> { sdkInstance.identify("bob") }

        justRun { userStore.saveUserId("bob") }
        sdkInstance.identify("bob")
        testScope.runCurrent()

        val update = outputReader.trackEvents.filter {
            it.event == EventNames.DEVICE_UPDATE && it.userId == "bob"
        }.shouldHaveSingleItem()
        update.context.deviceToken shouldBeEqualTo "token-a"
    }

    @Test
    fun testSetProfileAttributes_whenIdentifyIsPending_thenKeepsAcceptedProfile() {
        sdkInstance.identify("alice")
        sdkInstance.registerDeviceToken("token-a")
        testScope.runCurrent()

        sdkInstance.identify("bob")
        sdkInstance.userId shouldBeEqualTo "alice"
        sdkInstance.setProfileAttributes(mapOf("favorite_color" to "blue"))
        testScope.runCurrent()

        outputReader.identifyEvents.map { it.userId } shouldBeEqualTo listOf("alice", "bob", "bob")
        outputReader.trackEvents.filter { it.event == EventNames.DEVICE_DELETE }.map { it.userId } shouldBeEqualTo listOf("alice")
        sdkInstance.userId shouldBeEqualTo "bob"
    }

    @Test
    fun testDeviceEvents_whenSubmissionIsDelayed_thenKeepCallerTimestamps() {
        sdkInstance.identify("alice")
        testScope.runCurrent()
        val clock = mockk<Clock>()
        var currentTime = 1700000000123L
        every { clock.currentTimeMillis() } answers { currentTime }
        SDKComponent.overrideDependency<Clock>(clock)

        sdkInstance.registerDeviceToken("token-a")
        currentTime = 1700000001456L
        sdkInstance.setDeviceAttributes(mapOf("custom" to "value"))
        currentTime = 1700000067890L
        testScope.runCurrent()

        outputReader.trackEvents.map { it.timestamp } shouldBeEqualTo listOf(
            "2023-11-14T22:13:20.123Z",
            "2023-11-14T22:13:21.456Z"
        )
    }
}
