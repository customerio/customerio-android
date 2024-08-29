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
import io.customer.messaginginapp.gist.data.model.GistMessageProperties
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.MessagePosition
import io.customer.messaginginapp.gist.presentation.GistProvider
import io.customer.messaginginapp.testutils.core.JUnitTest
import io.customer.messaginginapp.testutils.extension.createInAppMessage
import io.customer.messaginginapp.type.InAppEventListener
import io.customer.messaginginapp.type.InAppMessage
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.ScopeProvider
import io.customer.sdk.data.model.Region
import io.customer.sdk.events.Metric
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

internal class ModuleMessagingInAppTest : JUnitTest() {
    private lateinit var module: ModuleMessagingInApp
    private lateinit var eventBus: EventBus
    private lateinit var inAppEventListenerMock: InAppEventListener
    private lateinit var inAppMessagesProviderMock: GistProvider
    private val testScopeProviderStub = ScopeProviderStub()

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<ScopeProvider>(testScopeProviderStub)
                        val spykEventBus = spyk(eventBus)
                        overrideDependency<EventBus>(spykEventBus)
                        overrideDependency(mockk<GistProvider>(relaxed = true))
                    }
                }
            }
        )

        inAppEventListenerMock = mockk(relaxed = true)
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
    fun initialize_givenProfileIdentified_expectGistToSetUserToken() {
        val givenIdentifier = String.random
        module.initialize()

        // publish profile identified event
        eventBus.publish(Event.ProfileIdentifiedEvent(identifier = givenIdentifier))
        // verify gist sets userToken
        assertCalledOnce { inAppMessagesProviderMock.setUserId(givenIdentifier) }
    }

    @Test
    fun initialize_givenProfilePreviouslyIdentified_expectGistToSetUserToken() {
        val givenIdentifier = String.random
        eventBus.publish(Event.ProfileIdentifiedEvent(identifier = givenIdentifier))

        module.initialize()

        // verify gist sets userToken
        assertCalledOnce { inAppMessagesProviderMock.setUserId(givenIdentifier) }
    }

    @Test
    fun initialize_givenNoProfileIdentified_expectGistNoUserSet() {
        module.initialize()

        // verify gist doesn't userToken
        assertCalledNever { inAppMessagesProviderMock.setUserId(any()) }
    }

    @Test
    fun whenDismissMessageCalledOnCustomerIO_thenDismissMessageIsCalledOnGist() {
        module.initialize()

        // call dismissMessage on the CustomerIO instance
        ModuleMessagingInApp.instance().dismissMessage()

        // verify that the module's dismissMessage method was called
        assertCalledOnce { inAppMessagesProviderMock.dismissMessage() }
    }

    @Test
    fun whenScreenViewedEventOccurs_expectGistProviderSetsCurrentRoute() {
        val givenRoute = "home_screen"
        module.initialize()

        eventBus.publish(Event.ScreenViewedEvent(name = givenRoute))

        assertCalledOnce { inAppMessagesProviderMock.setCurrentRoute(givenRoute) }
    }

    @Test
    fun whenResetEventOccurs_expectGistProviderResets() {
        module.initialize()

        eventBus.publish(Event.ResetEvent)

        assertCalledOnce { inAppMessagesProviderMock.reset() }
    }

    @Test
    fun initialize_givenMessageShownWithValidPosition_expectEventListenerNotifiedAndMetricEventPublished() {
        val message = createInAppMessage(position = "top")
        val inAppMessage = InAppMessage(
            messageId = message.messageId,
            deliveryId = "test_campaign_id",
            queueId = message.queueId
        )

        mockkObject(InAppMessage.Companion)
        every { InAppMessage.getFromGistMessage(any()) } returns inAppMessage

        module.initialize()
        module.onMessageShown(message)

        verify(exactly = 1) {
            InAppMessage.getFromGistMessage(any())
            inAppEventListenerMock.messageShown(inAppMessage)
            eventBus.publish(
                Event.TrackInAppMetricEvent(
                    deliveryID = "test_campaign_id",
                    event = Metric.Opened
                )
            )
        }

        val messageSlot = slot<Message>()
        verify { InAppMessage.getFromGistMessage(capture(messageSlot)) }
        val capturedMessage = messageSlot.captured
        val gistProperties = GistMessageProperties.getGistProperties(capturedMessage)
        assert(gistProperties.position == MessagePosition.TOP)
    }

    @Test
    fun initialize_givenMessageShownWithNullPosition_expectDefaultPositionUsed() {
        val message = createInAppMessage(position = null)
        val inAppMessage = InAppMessage(
            messageId = message.messageId,
            deliveryId = "test_campaign_id",
            queueId = message.queueId
        )

        mockkObject(InAppMessage.Companion)
        every { InAppMessage.getFromGistMessage(any()) } returns inAppMessage

        module.initialize()
        module.onMessageShown(message)

        assertCalledOnce { inAppEventListenerMock.messageShown(inAppMessage) }

        val messageSlot = slot<Message>()
        verify { InAppMessage.getFromGistMessage(capture(messageSlot)) }
        val capturedMessage = messageSlot.captured
        val gistProperties = GistMessageProperties.getGistProperties(capturedMessage)
        assert(gistProperties.position == MessagePosition.CENTER)
    }

    @Test
    fun onAction_givenNonCloseAction_expectTrackInAppMetricEventPublished() {
        val message = createInAppMessage(campaignId = "test_campaign_id")
        val inAppMessage = InAppMessage(
            messageId = message.messageId,
            deliveryId = "test_campaign_id",
            queueId = message.queueId
        )

        mockkObject(InAppMessage.Companion)
        every { InAppMessage.getFromGistMessage(any()) } returns inAppMessage

        module.initialize()
        module.onAction(message, "current_route", "test_action", "Test Action")

        verify(exactly = 1) {
            inAppEventListenerMock.messageActionTaken(inAppMessage, "test_action", "Test Action")
            eventBus.publish(
                Event.TrackInAppMetricEvent(
                    deliveryID = "test_campaign_id",
                    event = Metric.Clicked,
                    params = mapOf("action_name" to "Test Action", "action_value" to "test_action")
                )
            )
        }
    }

    @Test
    fun onAction_givenCloseAction_expectNoTrackInAppMetricEventPublished() = runTest {
        val message = createInAppMessage(campaignId = "test_campaign_id")
        val inAppMessage = InAppMessage(
            messageId = message.messageId,
            deliveryId = "test_campaign_id",
            queueId = message.queueId
        )

        mockkObject(InAppMessage.Companion)
        every { InAppMessage.getFromGistMessage(any()) } returns inAppMessage

        module.initialize()
        module.onAction(message, "current_route", "gist://close", "Close")

        verify(exactly = 1) {
            inAppEventListenerMock.messageActionTaken(inAppMessage, "gist://close", "Close")
        }
        verify(exactly = 0) {
            eventBus.publish(any<Event.TrackInAppMetricEvent>())
        }
    }
}
