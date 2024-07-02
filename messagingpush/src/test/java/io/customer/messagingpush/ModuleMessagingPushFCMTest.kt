package io.customer.messagingpush

import android.app.Application
import io.customer.commontest.extensions.random
import io.customer.messagingpush.provider.DeviceTokenProvider
import io.customer.messagingpush.testutils.core.JUnitTest
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.registerAndroidSDKComponent
import io.customer.sdk.data.store.Client
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

internal class ModuleMessagingPushFCMTest : JUnitTest() {

    private val fcmTokenProviderMock: DeviceTokenProvider = mockk(relaxed = true)

    private val applicationContextMock: Application = mockk(relaxed = true)

    private lateinit var eventBus: EventBus
    private lateinit var module: ModuleMessagingPushFCM

    init {
        every { applicationContextMock.applicationContext } returns applicationContextMock
    }

    override fun setupTestEnvironment() {
        super.setupTestEnvironment()
        module = ModuleMessagingPushFCM()
        eventBus = SDKComponent.eventBus
    }

    override fun setupSDKComponent() {
        super.setupSDKComponent()

        // Because we are not initializing the SDK, we need to register the
        // Android SDK component manually so that the module can utilize it
        val androidSDKComponent = SDKComponent.registerAndroidSDKComponent(applicationContextMock, Client.Android(sdkVersion = "3.0.0"))
        androidSDKComponent.overrideDependency(DeviceTokenProvider::class.java, fcmTokenProviderMock)
        SDKComponent.overrideDependency(MessagingPushModuleConfig::class.java, MessagingPushModuleConfig.default())
        SDKComponent.overrideDependency(EventBus::class.java, mockk(relaxed = true))
    }

    override fun teardown() {
        eventBus.removeAllSubscriptions()

        super.teardown()
    }

    @Test
    fun initialize_givenNoFCMTokenAvailable_expectDoNotRegisterToken() {
        every { fcmTokenProviderMock.getCurrentToken(any()) } answers {
            val callback = firstArg<(String?) -> Unit>()
            callback(null)
        }

        module.initialize()

        verify(exactly = 0) { eventBus.publish(any<Event.RegisterDeviceTokenEvent>()) }
    }

    @Test
    fun initialize_givenFCMTokenAvailable_expectRegisterToken() {
        val givenToken = String.random

        every { fcmTokenProviderMock.getCurrentToken(any()) } answers {
            val callback = firstArg<(String?) -> Unit>()
            callback(givenToken)
        }

        module.initialize()

        verify(exactly = 1) { eventBus.publish(Event.RegisterDeviceTokenEvent(token = givenToken)) }
    }
}
