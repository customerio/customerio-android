package io.customer.messaginginapp

import android.app.Application
import io.customer.commontest.util.ScopeProviderStub
import io.customer.messaginginapp.di.inAppMessaging
import io.customer.messaginginapp.provider.InAppMessagesProvider
import io.customer.messaginginapp.support.core.JUnitTest
import io.customer.messaginginapp.type.InAppEventListener
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.registerAndroidSDKComponent
import io.customer.sdk.core.util.ScopeProvider
import io.customer.sdk.data.model.Region
import io.customer.sdk.data.store.Client
import io.customer.sdk.extensions.random
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

internal class ModuleMessagingInAppTest : JUnitTest() {
    private lateinit var eventBus: EventBus
    private lateinit var module: ModuleMessagingInApp

    private val applicationContextMock: Application = mockk(relaxed = true)
    private val inAppEventListenerMock: InAppEventListener = mockk(relaxed = true)
    private val inAppMessagesProviderMock: InAppMessagesProvider = mockk(relaxed = true)
    private val testScopeProviderStub = ScopeProviderStub()

    private val moduleConfig: MessagingInAppModuleConfig
        get() = module.moduleConfig

    init {
        every { applicationContextMock.applicationContext } returns applicationContextMock
    }

    override fun setupTestEnvironment() {
        super.setupTestEnvironment()

        eventBus = SDKComponent.eventBus
        module = ModuleMessagingInApp(
            config = MessagingInAppModuleConfig.Builder(
                siteId = TEST_SITE_ID,
                region = Region.US
            ).setEventListener(inAppEventListenerMock).build()
        )
        SDKComponent.modules[ModuleMessagingInApp.MODULE_NAME] = module
    }

    override fun setupSDKComponent() {
        super.setupSDKComponent()

        SDKComponent.overrideDependency(ScopeProvider::class.java, testScopeProviderStub)
        SDKComponent.overrideDependency(InAppMessagesProvider::class.java, inAppMessagesProviderMock)
        // Because we are not initializing the SDK, we need to register the
        // Android SDK component manually so that the module can utilize it
        SDKComponent.registerAndroidSDKComponent(applicationContextMock, Client.Android(sdkVersion = "3.0.0"))
    }

    override fun teardown() {
        eventBus.removeAllSubscriptions()

        super.teardown()
    }

    @Test
    fun initialize_givenComponentInitialize_expectGistToInitializeWithCorrectValuesAndHooks() {
        module.initialize()

        // verify gist is initialized
        verify(exactly = 1) {
            inAppMessagesProviderMock.initProvider(
                any(),
                moduleConfig.siteId,
                moduleConfig.region.code
            )
        }

        // verify events
        verify(exactly = 1) { inAppMessagesProviderMock.subscribeToEvents(any(), any(), any()) }

        // verify given event listener gets registered
        verify(exactly = 1) { inAppMessagesProviderMock.setListener(inAppEventListenerMock) }
    }

    @Test
    fun initialize_givenProfileIdentified_expectGistToSetUserToken() {
        val givenIdentifier = String.random
        module.initialize()

        // verify gist is initialized
        verify(exactly = 1) {
            inAppMessagesProviderMock.initProvider(
                any(),
                moduleConfig.siteId,
                moduleConfig.region.code
            )
        }

        // publish profile identified event
        eventBus.publish(Event.ProfileIdentifiedEvent(identifier = givenIdentifier))
        // verify gist sets userToken
        verify(exactly = 1) { inAppMessagesProviderMock.setUserToken(givenIdentifier) }
    }

    @Test
    fun initialize_givenProfilePreviouslyIdentified_expectGistToSetUserToken() {
        val givenIdentifier = String.random
        eventBus.publish(Event.ProfileIdentifiedEvent(identifier = givenIdentifier))

        module.initialize()

        // verify gist is initialized
        verify(exactly = 1) {
            inAppMessagesProviderMock.initProvider(
                any(),
                moduleConfig.siteId,
                moduleConfig.region.code
            )
        }

        // verify gist sets userToken
        verify(exactly = 1) { inAppMessagesProviderMock.setUserToken(givenIdentifier) }
    }

    @Test
    fun initialize_givenNoProfileIdentified_expectGistNoUserSet() {
        module.initialize()

        // verify gist is initialized
        verify(exactly = 1) {
            inAppMessagesProviderMock.initProvider(
                any(),
                moduleConfig.siteId,
                moduleConfig.region.code
            )
        }

        // verify gist doesn't userToken
        verify(exactly = 0) { inAppMessagesProviderMock.setUserToken(any()) }
    }

    @Test
    fun whenDismissMessageCalledOnCustomerIO_thenDismissMessageIsCalledOnGist() {
        module.initialize()

        // call dismissMessage on the CustomerIO instance
        SDKComponent.inAppMessaging().dismissMessage()

        // verify that the module's dismissMessage method was called
        verify(exactly = 1) { inAppMessagesProviderMock.dismissMessage() }
    }
}
