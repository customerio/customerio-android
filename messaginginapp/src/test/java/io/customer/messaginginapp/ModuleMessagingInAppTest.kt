package io.customer.messaginginapp

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.TestConstants
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.commontest.extensions.random
import io.customer.commontest.util.ScopeProviderStub
import io.customer.messaginginapp.di.gistCustomAttributes
import io.customer.messaginginapp.di.gistProvider
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.MessagePosition
import io.customer.messaginginapp.gist.presentation.GistProvider
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.MessageBuilderMock.createMessage
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ModuleMessagingInAppTest : JUnitTest() {
    private lateinit var module: ModuleMessagingInApp
    private lateinit var eventBus: EventBus
    private lateinit var inAppEventListenerMock: InAppEventListener
    private lateinit var inAppMessagesProviderMock: GistProvider
    private val testScopeProviderStub = ScopeProviderStub.Unconfined()

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<ScopeProvider>(testScopeProviderStub)
                        overrideDependency<EventBus>(spyk(eventBus))
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
        SDKComponent.gistCustomAttributes.clear()

        super.teardown()
    }

    @Test
    fun initialize_givenUserChangedWithUserId_expectGistSetsBothIdsAndFetches() {
        val givenUserId = String.random
        val givenAnonymousId = String.random
        module.initialize()

        // publish user changed event with userId (identified user)
        eventBus.publish(Event.UserChangedEvent(userId = givenUserId, anonymousId = givenAnonymousId))

        // verify gist sets both anonymousId and userId, then fetches
        assertCalledOnce { inAppMessagesProviderMock.setAnonymousId(givenAnonymousId) }
        assertCalledOnce { inAppMessagesProviderMock.setUserId(givenUserId) }
        assertCalledOnce { inAppMessagesProviderMock.fetchInAppMessages() }
        // verify custom attribute is set
        assert(SDKComponent.gistCustomAttributes["cio_anonymous_id"] == givenAnonymousId)
    }

    @Test
    fun initialize_givenUserChangedWithoutUserId_expectGistSetsAnonymousIdAndFetches() {
        val givenAnonymousId = String.random
        module.initialize()

        // publish user changed event without userId (anonymous user)
        eventBus.publish(Event.UserChangedEvent(userId = null, anonymousId = givenAnonymousId))

        // verify gist sets anonymousId and fetches
        assertCalledOnce { inAppMessagesProviderMock.setAnonymousId(givenAnonymousId) }
        assertCalledOnce { inAppMessagesProviderMock.fetchInAppMessages() }
        // verify custom attribute is set
        assert(SDKComponent.gistCustomAttributes["cio_anonymous_id"] == givenAnonymousId)
    }

    @Test
    fun initialize_givenNoUserChangedEvent_expectGistNoUserSetAndNoFetch() {
        module.initialize()

        // verify gist doesn't set any user info or fetch
        assertCalledNever { inAppMessagesProviderMock.setUserId(any()) }
        assertCalledNever { inAppMessagesProviderMock.setAnonymousId(any()) }
        assertCalledNever { inAppMessagesProviderMock.fetchInAppMessages() }
    }

    @Test
    fun initialize_givenUserChangedEventPublishedBeforeInit_expectGistSetsBothIdsAndFetches() {
        val givenUserId = String.random
        val givenAnonymousId = String.random
        eventBus.publish(Event.UserChangedEvent(userId = givenUserId, anonymousId = givenAnonymousId))

        module.initialize()

        // verify gist sets both ids and fetches (event should be replayed)
        assertCalledOnce { inAppMessagesProviderMock.setAnonymousId(givenAnonymousId) }
        assertCalledOnce { inAppMessagesProviderMock.setUserId(givenUserId) }
        assertCalledOnce { inAppMessagesProviderMock.fetchInAppMessages() }
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
    fun observeInlineMessageAvailability_givenMessageStateChanges_expectCurrentAvailability() = runTest {
        val elementId = String.random
        val message = createMessage(elementId = elementId)
        val availability = module.observeInlineMessageAvailability(elementId)
        val manager = SDKComponent.inAppMessagingManager

        assertFalse(availability.first())

        manager.dispatch(InAppMessagingAction.EmbedMessages(listOf(message)))
        assertTrue(availability.first())

        manager.dispatch(InAppMessagingAction.DisplayMessage(message))
        assertTrue(availability.first())

        manager.dispatch(InAppMessagingAction.DismissMessage(message))
        assertFalse(availability.first())
    }

    @Test
    fun observeInlineMessageAvailability_givenRouteStopsMatching_expectUnavailable() = runTest {
        val elementId = String.random
        val message = createMessage(elementId = elementId, routeRule = "home/.*")
        val manager = SDKComponent.inAppMessagingManager
        manager.dispatch(InAppMessagingAction.SetPageRoute("home/feed"))
        manager.dispatch(InAppMessagingAction.EmbedMessages(listOf(message)))

        assertTrue(module.observeInlineMessageAvailability(elementId).first())

        manager.dispatch(InAppMessagingAction.SetPageRoute("settings"))

        assertFalse(module.observeInlineMessageAvailability(elementId).first())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeInlineMessageAvailability_givenUnrelatedStateChanges_expectDistinctValues() = runTest {
        val elementId = String.random
        val message = createMessage(elementId = elementId)
        val emissions = mutableListOf<Boolean>()
        val collectionJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            module.observeInlineMessageAvailability(elementId).take(2).toList(emissions)
        }

        SDKComponent.inAppMessagingManager.dispatch(InAppMessagingAction.SetColorScheme(io.customer.messaginginapp.type.ColorScheme.DARK))
        SDKComponent.inAppMessagingManager.dispatch(InAppMessagingAction.EmbedMessages(listOf(message)))
        collectionJob.join()

        assertEquals(listOf(false, true), emissions)
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
    fun whenResetEventOccurs_expectCustomAttributesCleared() {
        module.initialize()
        val givenAnonymousId = String.random
        eventBus.publish(Event.UserChangedEvent(userId = null, anonymousId = givenAnonymousId))

        eventBus.publish(Event.ResetEvent)

        assert(SDKComponent.gistCustomAttributes.isEmpty())
    }

    @Test
    fun whenResetEventFollowedByUserChanged_expectSingleFetchAfterReset() {
        module.initialize()
        val givenUserId = String.random
        val givenAnonymousId = String.random

        // Set up identified user
        eventBus.publish(Event.UserChangedEvent(userId = givenUserId, anonymousId = givenAnonymousId))

        // Clear mock invocations
        io.mockk.clearMocks(inAppMessagesProviderMock, answers = false)

        // Simulate clearIdentify flow: ResetEvent followed by UserChangedEvent with new anonymous ID
        val newAnonymousId = String.random
        eventBus.publish(Event.ResetEvent)
        eventBus.publish(Event.UserChangedEvent(userId = null, anonymousId = newAnonymousId))

        // Verify reset was called
        assertCalledOnce { inAppMessagesProviderMock.reset() }
        // Verify anonymous ID was set and fetch was triggered (single fetch after user change)
        assertCalledOnce { inAppMessagesProviderMock.setAnonymousId(newAnonymousId) }
        assertCalledOnce { inAppMessagesProviderMock.fetchInAppMessages() }
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
        val gistProperties = capturedMessage.gistProperties
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
        val gistProperties = capturedMessage.gistProperties
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
                    params = mapOf("actionName" to "Test Action", "actionValue" to "test_action")
                )
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
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
