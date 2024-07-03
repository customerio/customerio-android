package io.customer.messaginginapp

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.TestConstants
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.commontest.extensions.random
import io.customer.commontest.util.ScopeProviderStub
import io.customer.messaginginapp.di.gistProvider
import io.customer.messaginginapp.di.inAppMessaging
import io.customer.messaginginapp.provider.InAppMessagesProvider
import io.customer.messaginginapp.testutils.core.JUnitTest
import io.customer.messaginginapp.type.InAppEventListener
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.ScopeProvider
import io.customer.sdk.data.model.Region
import io.mockk.mockk
import org.junit.jupiter.api.Test

internal class ModuleMessagingInAppTest : JUnitTest() {
    private lateinit var eventBus: EventBus
    private lateinit var inAppMessagesProviderMock: InAppMessagesProvider
    private lateinit var module: ModuleMessagingInApp

    private val inAppEventListenerMock: InAppEventListener = mockk(relaxed = true)
    private val testScopeProviderStub = ScopeProviderStub()

    private val moduleConfig: MessagingInAppModuleConfig
        get() = module.moduleConfig

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<ScopeProvider>(testScopeProviderStub)
                        overrideDependency(mockk<InAppMessagesProvider>(relaxed = true))
                    }
                }
            }
        )

        eventBus = SDKComponent.eventBus
        inAppMessagesProviderMock = SDKComponent.gistProvider

        module = ModuleMessagingInApp(
            config = MessagingInAppModuleConfig.Builder(
                siteId = TestConstants.Keys.SITE_ID,
                region = Region.US
            ).setEventListener(inAppEventListenerMock).build()
        ).attachToSDKComponent()
    }

    override fun teardown() {
        eventBus.removeAllSubscriptions()

        super.teardown()
    }

    @Test
    fun initialize_givenComponentInitialize_expectGistToInitializeWithCorrectValuesAndHooks() {
        module.initialize()

        // verify gist is initialized
        assertCalledOnce {
            inAppMessagesProviderMock.initProvider(
                any(),
                moduleConfig.siteId,
                moduleConfig.region.code
            )
        }

        // verify events
        assertCalledOnce { inAppMessagesProviderMock.subscribeToEvents(any(), any(), any()) }

        // verify given event listener gets registered
        assertCalledOnce { inAppMessagesProviderMock.setListener(inAppEventListenerMock) }
    }

    @Test
    fun initialize_givenProfileIdentified_expectGistToSetUserToken() {
        val givenIdentifier = String.random
        module.initialize()

        // verify gist is initialized
        assertCalledOnce {
            inAppMessagesProviderMock.initProvider(
                any(),
                moduleConfig.siteId,
                moduleConfig.region.code
            )
        }

        // publish profile identified event
        eventBus.publish(Event.ProfileIdentifiedEvent(identifier = givenIdentifier))
        // verify gist sets userToken
        assertCalledOnce { inAppMessagesProviderMock.setUserToken(givenIdentifier) }
    }

    @Test
    fun initialize_givenProfilePreviouslyIdentified_expectGistToSetUserToken() {
        val givenIdentifier = String.random
        eventBus.publish(Event.ProfileIdentifiedEvent(identifier = givenIdentifier))

        module.initialize()

        // verify gist is initialized
        assertCalledOnce {
            inAppMessagesProviderMock.initProvider(
                any(),
                moduleConfig.siteId,
                moduleConfig.region.code
            )
        }

        // verify gist sets userToken
        assertCalledOnce { inAppMessagesProviderMock.setUserToken(givenIdentifier) }
    }

    @Test
    fun initialize_givenNoProfileIdentified_expectGistNoUserSet() {
        module.initialize()

        // verify gist is initialized
        assertCalledOnce {
            inAppMessagesProviderMock.initProvider(
                any(),
                moduleConfig.siteId,
                moduleConfig.region.code
            )
        }

        // verify gist doesn't userToken
        assertCalledNever { inAppMessagesProviderMock.setUserToken(any()) }
    }

    @Test
    fun whenDismissMessageCalledOnCustomerIO_thenDismissMessageIsCalledOnGist() {
        module.initialize()

        // call dismissMessage on the CustomerIO instance
        SDKComponent.inAppMessaging().dismissMessage()

        // verify that the module's dismissMessage method was called
        assertCalledOnce { inAppMessagesProviderMock.dismissMessage() }
    }
}
