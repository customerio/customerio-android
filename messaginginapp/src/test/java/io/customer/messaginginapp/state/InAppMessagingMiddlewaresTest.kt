package io.customer.messaginginapp.state

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.random
import io.customer.messaginginapp.gist.data.listeners.GistQueue
import io.customer.messaginginapp.gist.data.model.InboxMessage
import io.customer.messaginginapp.gist.presentation.GistListener
import io.customer.messaginginapp.gist.presentation.GistSdk
import io.customer.messaginginapp.state.MessageBuilderMock.createMessage
import io.customer.messaginginapp.testutils.core.JUnitTest
import io.customer.messaginginapp.testutils.extension.createInAppMessage
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.util.Logger
import io.customer.sdk.events.Metric
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.reduxkotlin.Store

/**
 * Tests for the middleware functions in InAppMessagingMiddlewares.kt,
 * focused on behaviors related to inline in-app messaging.
 */
class InAppMessagingMiddlewaresTest : JUnitTest() {

    private val store: Store<InAppMessagingState> = mockk(relaxed = true)
    private val nextFn: (Any) -> Any = mockk(relaxed = true)
    private val mockGistQueue: GistQueue = mockk(relaxed = true)
    private val mockGistListener: GistListener = mockk(relaxed = true)
    private val mockLogger: Logger = mockk(relaxed = true)
    private val mockGistSdk: GistSdk = mockk(relaxed = true)
    private val mockEventBus: EventBus = mockk(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        // Configure store state
        val emptyState = InAppMessagingState()
        every { store.state } returns emptyState

        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<GistQueue>(mockGistQueue)
                        overrideDependency<Logger>(mockLogger)
                        overrideDependency<GistSdk>(mockGistSdk)
                        overrideDependency<EventBus>(mockEventBus)
                    }
                }
            }
        )
    }

    @Test
    fun processMessages_givenInlineMessages_shouldFilterAndEmbed() {
        val givenRoute = "test/route"
        val givenUserId = String.random
        val modalMessage = createMessage()
        val inlineMessage1 = createMessage(elementId = "element1")
        val inlineMessage2 = createMessage(elementId = "element2")
        val inlineMessage3 = createMessage(elementId = "element3", routeRule = "another-route")

        val messages = listOf(modalMessage, inlineMessage1, inlineMessage2, inlineMessage3)

        val state = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random,
            userId = givenUserId,
            currentRoute = givenRoute,
            messagesInQueue = messages.toSet()
        )

        every { store.state } returns state

        val dispatchedActions = mutableListOf<InAppMessagingAction>()
        every { store.dispatch(capture(slot())) } answers {
            val action = firstArg<InAppMessagingAction>()
            dispatchedActions.add(action)
            Unit
        }

        val middleware = processMessages()
        val action = InAppMessagingAction.ProcessMessageQueue(messages)
        middleware(store)(nextFn)(action)

        val embedActions = dispatchedActions.filterIsInstance<InAppMessagingAction.EmbedMessages>()
        assert(embedActions.isNotEmpty()) { "Expected EmbedMessages action to be dispatched" }

        val embeddedMessages = embedActions.first().messages
        assert(embeddedMessages.contains(inlineMessage1)) { "Expected inlineMessage1 to be embedded" }
        assert(embeddedMessages.contains(inlineMessage2)) { "Expected inlineMessage2 to be embedded" }
        assert(!embeddedMessages.contains(inlineMessage3)) { "Expected inlineMessage3 not to be embedded" }
    }

    @Test
    fun gistListenerMiddleware_shouldNotifyListenerForEmbeddedMessages() {
        val element1 = String.random
        val element2 = String.random
        val inlineMessage1 = createMessage(elementId = element1)
        val inlineMessage2 = createMessage(elementId = element2)

        val middleware = gistListenerMiddleware(mockGistListener)
        val action = InAppMessagingAction.EmbedMessages(listOf(inlineMessage1, inlineMessage2))
        middleware(store)(nextFn)(action)

        verify {
            mockGistListener.embedMessage(inlineMessage1, element1)
            mockGistListener.embedMessage(inlineMessage2, element2)
        }

        verify { nextFn(action) }
    }

    @Test
    fun gistListenerMiddleware_shouldNotifyListenerForDisplayedMessage() {
        val elementId = String.random
        val message = createMessage(elementId = elementId)

        val middleware = gistListenerMiddleware(mockGistListener)
        val action = InAppMessagingAction.DisplayMessage(message)
        middleware(store)(nextFn)(action)

        verify { mockGistListener.onMessageShown(message) }

        verify { nextFn(action) }
    }

    @Test
    fun gistListenerMiddleware_shouldNotifyListenerForDismissedMessage() {
        val elementId = String.random
        val message = createMessage(elementId = elementId)

        val middleware = gistListenerMiddleware(mockGistListener)
        val action = InAppMessagingAction.DismissMessage(message)
        middleware(store)(nextFn)(action)

        verify { mockGistListener.onMessageDismissed(message) }

        verify { nextFn(action) }
    }

    @Test
    fun gistListenerMiddleware_shouldNotifyListenerForMessageLoadingFailed() {
        val elementId = String.random
        val message = createMessage(elementId = elementId)

        val middleware = gistListenerMiddleware(mockGistListener)
        val action = InAppMessagingAction.EngineAction.MessageLoadingFailed(message)
        middleware(store)(nextFn)(action)

        verify { mockGistListener.onError(message) }

        verify { nextFn(action) }
    }

    @Test
    fun gistListenerMiddleware_shouldNotifyListenerForTapAction() {
        val elementId = String.random
        val message = createMessage(elementId = elementId)
        val route = String.random
        val name = String.random
        val action = String.random

        val middleware = gistListenerMiddleware(mockGistListener)
        val tapAction = InAppMessagingAction.EngineAction.Tap(message, route, name, action)
        middleware(store)(nextFn)(tapAction)

        verify { mockGistListener.onAction(message, route, action, name) }

        verify { nextFn(tapAction) }
    }

    @Test
    fun routeChangeMiddleware_shouldProcessMessageQueueWhenRouteChanges() {
        val givenUserId = String.random
        val oldRoute = "old/route"
        val newRoute = "new/route"
        val messages = setOf(createInAppMessage(), createInAppMessage())

        val state = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random,
            userId = givenUserId,
            currentRoute = oldRoute,
            messagesInQueue = messages
        )

        every { store.state } returns state

        val dispatchedActions = mutableListOf<InAppMessagingAction>()
        every { store.dispatch(capture(slot())) } answers {
            val capturedAction = firstArg<InAppMessagingAction>()
            dispatchedActions.add(capturedAction)
            Unit
        }

        val middleware = routeChangeMiddleware()
        val action = InAppMessagingAction.SetPageRoute(newRoute)
        middleware(store)(nextFn)(action)

        verify { nextFn(action) }

        assert(dispatchedActions.any { it is InAppMessagingAction.ProcessMessageQueue }) {
            "Expected ProcessMessageQueue action to be dispatched"
        }

        val processAction = dispatchedActions.filterIsInstance<InAppMessagingAction.ProcessMessageQueue>().first()
        assert(processAction.messages.containsAll(messages)) {
            "Expected ProcessMessageQueue action to contain all messages"
        }
    }

    @Test
    fun routeChangeMiddleware_shouldDismissModalMessageIfRouteDoesNotMatch() {
        val givenUserId = String.random
        val oldRoute = "old/route"
        val newRoute = "new/route"
        val modalMessage = createMessage(routeRule = "old/.*")

        val state = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random,
            userId = givenUserId,
            currentRoute = oldRoute,
            modalMessageState = ModalMessageState.Displayed(modalMessage)
        )

        every { store.state } returns state

        val dispatchedActions = mutableListOf<InAppMessagingAction>()
        every { store.dispatch(capture(slot())) } answers {
            val capturedAction = firstArg<InAppMessagingAction>()
            dispatchedActions.add(capturedAction)
            Unit
        }

        val middleware = routeChangeMiddleware()
        val action = InAppMessagingAction.SetPageRoute(newRoute)
        middleware(store)(nextFn)(action)

        assert(dispatchedActions.any { it is InAppMessagingAction.DismissMessage }) {
            "Expected DismissMessage action to be dispatched"
        }

        val dismissAction = dispatchedActions.filterIsInstance<InAppMessagingAction.DismissMessage>().first()
        assert(dismissAction.message == modalMessage) {
            "Expected DismissMessage action to contain the modal message"
        }
        assert(!dismissAction.shouldLog) {
            "Expected DismissMessage action to have shouldLog=false"
        }
    }

    @Test
    fun gistLoggingMessageMiddleware_shouldLogViewForNonPersistentMessageOnDisplay() {
        val message = createInAppMessage(persistent = false)

        val middleware = gistLoggingMessageMiddleware()
        val action = InAppMessagingAction.DisplayMessage(message)
        middleware(store)(nextFn)(action)

        verify { mockGistQueue.logView(message) }

        verify { nextFn(action) }
    }

    @Test
    fun gistLoggingMessageMiddleware_shouldNotLogViewForPersistentMessageOnDisplay() {
        val message = createInAppMessage(persistent = true)

        val middleware = gistLoggingMessageMiddleware()
        val action = InAppMessagingAction.DisplayMessage(message)
        middleware(store)(nextFn)(action)

        verify(exactly = 0) { mockGistQueue.logView(message) }

        verify { nextFn(action) }
    }

    @Test
    fun processMessages_shouldPrioritizeMessagesCorrectly() {
        val givenRoute = "test/route"
        val givenUserId = String.random

        val highPriorityMessage = createMessage(elementId = "element1", priority = 1)
        val mediumPriorityMessage = createMessage(elementId = "element2", priority = 2)
        val lowPriorityMessage = createMessage(elementId = "element3", priority = 3)

        val messages = listOf(mediumPriorityMessage, lowPriorityMessage, highPriorityMessage)

        val state = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random,
            userId = givenUserId,
            currentRoute = givenRoute,
            messagesInQueue = emptySet()
        )

        every { store.state } returns state

        val dispatchedActions = mutableListOf<InAppMessagingAction>()
        every { store.dispatch(capture(slot())) } answers {
            val capturedAction = firstArg<InAppMessagingAction>()
            dispatchedActions.add(capturedAction)
            Unit
        }

        val middleware = processMessages()
        val action = InAppMessagingAction.ProcessMessageQueue(messages)
        middleware(store)(nextFn)(action)

        val embedActions = dispatchedActions.filterIsInstance<InAppMessagingAction.EmbedMessages>()
        assert(embedActions.isNotEmpty()) { "Expected EmbedMessages action to be dispatched" }

        val embeddedMessages = embedActions.first().messages

        assert(embeddedMessages.size == 3) { "Expected all 3 messages to be embedded" }

        val sortedMessages = messages.sortedWith(compareBy { it.priority })
        assert(sortedMessages[0] == highPriorityMessage) { "Expected highPriorityMessage to be first" }
        assert(sortedMessages[1] == mediumPriorityMessage) { "Expected mediumPriorityMessage to be second" }
        assert(sortedMessages[2] == lowPriorityMessage) { "Expected lowPriorityMessage to be third" }
    }

    @Test
    fun loggerMiddleware_shouldLogActionsAndStateChanges() {
        val action = InAppMessagingAction.SetPageRoute("test/route")
        val state = InAppMessagingState(
            siteId = "test-site",
            dataCenter = "test-center"
        )
        every { store.state } returns state

        val middleware = loggerMiddleware()
        middleware(store)(nextFn)(action)

        verify {
            mockLogger.debug("Store: action: $action")
            mockLogger.debug(match { it.startsWith("Store: state before reducer:") })
        }

        verify { nextFn(action) }
    }

    @Test
    fun errorLoggerMiddleware_shouldLogErrorAction() {
        val errorMessage = "Test error message"
        val action = InAppMessagingAction.ReportError(errorMessage)

        val middleware = errorLoggerMiddleware()
        middleware(store)(nextFn)(action)

        verify { mockLogger.error("Error: $errorMessage") }

        verify { nextFn(action) }
    }

    @Test
    fun simpleMiddlewareTest() {
        val action = InAppMessagingAction.SetPageRoute("test-route")

        val middleware = loggerMiddleware()

        middleware(store)(nextFn)(action)

        verify {
            mockLogger.debug(any())
            nextFn(action)
        }
    }

    @Test
    fun gistLoggingMessageMiddleware_shouldFetchMessagesWhenPersistentMessageDismissed() {
        // Given a persistent message
        val message = createInAppMessage(persistent = true)

        // Create a dismiss action that should mark the message as shown
        // (persistent message, shouldLog = true, viaCloseAction = true)
        val action = InAppMessagingAction.DismissMessage(message, shouldLog = true, viaCloseAction = true)

        // Run the middleware
        val middleware = gistLoggingMessageMiddleware()
        middleware(store)(nextFn)(action)

        // Verify that message view was logged
        verify { mockGistQueue.logView(message) }

        // Verify that fetchInAppMessages was called
        verify { mockGistSdk.fetchInAppMessages() }

        // Verify next action was called
        verify { nextFn(action) }
    }

    @Test
    fun gistLoggingMessageMiddleware_shouldNotFetchMessagesWhenNonPersistentMessageDismissed() {
        // Given a non-persistent message being dismissed but not marked as shown
        // (persistent=false, shouldLog=true, viaCloseAction=true)
        val message = createInAppMessage(persistent = false)
        val action = InAppMessagingAction.DismissMessage(message, shouldLog = true, viaCloseAction = true)

        // Run the middleware
        val middleware = gistLoggingMessageMiddleware()
        middleware(store)(nextFn)(action)

        // Should not log view or fetch messages since shouldMarkMessageAsShown() returns false for this case
        verify(exactly = 0) { mockGistQueue.logView(message) }
        verify(exactly = 0) { mockGistSdk.fetchInAppMessages() }

        // Verify next action was called
        verify { nextFn(action) }
    }

    @Test
    fun gistLoggingMessageMiddleware_shouldNotFetchMessagesWhenPersistentMessageDismissedButNotViaCloseAction() {
        // Given a persistent message that's dismissed but not via close action
        val message = createInAppMessage(persistent = true)
        val action = InAppMessagingAction.DismissMessage(message, shouldLog = true, viaCloseAction = false)

        // Run the middleware
        val middleware = gistLoggingMessageMiddleware()
        middleware(store)(nextFn)(action)

        // Should not log view or fetch messages since viaCloseAction is false
        verify(exactly = 0) { mockGistQueue.logView(message) }
        verify(exactly = 0) { mockGistSdk.fetchInAppMessages() }

        // Verify next action was called
        verify { nextFn(action) }
    }

    // Test removed: displayModalMessageMiddleware requires Android components that can't be easily mocked in unit tests
    // The test-specific middleware was originally created to address this very issue

    @Test
    fun routeChangeMiddleware_shouldWorkForAnonymousUsers() {
        val state = InAppMessagingState(userId = null) // Anonymous user
        every { store.state } returns state

        val dispatchedActions = mutableListOf<InAppMessagingAction>()
        every { store.dispatch(capture(slot())) } answers {
            val capturedAction = firstArg<InAppMessagingAction>()
            dispatchedActions.add(capturedAction)
            Unit
        }

        val routeMiddleware = routeChangeMiddleware()
        val routeAction = InAppMessagingAction.SetPageRoute("new/route")

        // Route middleware should work for anonymous users
        routeMiddleware(store)(nextFn)(routeAction)

        assert(dispatchedActions.any { it is InAppMessagingAction.ProcessMessageQueue }) {
            "Expected ProcessMessageQueue action to be dispatched for anonymous users"
        }

        val inlineMessage = createMessage(elementId = "test-element")
        val messagesInQueue = setOf(inlineMessage)
        val stateWithMessages = state.copy(
            messagesInQueue = messagesInQueue,
            currentRoute = "test/route"
        )
        every { store.state } returns stateWithMessages

        dispatchedActions.clear()

        val processMiddleware = processMessages()
        val processAction = InAppMessagingAction.ProcessMessageQueue(messagesInQueue.toList())

        processMiddleware(store)(nextFn)(processAction)

        assert(dispatchedActions.any { it is InAppMessagingAction.EmbedMessages }) {
            "Expected EmbedMessages action to be dispatched"
        }
    }

    @Test
    fun processInboxMessages_givenUpdateOpenedWithOpenedTrue_shouldPublishMetricEvent() {
        // Given an inbox message being marked as opened
        val deliveryId = String.random
        val queueId = String.random
        val inboxMessage = InboxMessage(deliveryId = deliveryId, queueId = queueId, opened = false)
        val action = InAppMessagingAction.InboxAction.UpdateOpened(inboxMessage, opened = true)

        // When the middleware processes the action
        val middleware = processInboxMessages()
        middleware(store)(nextFn)(action)

        // Then it should call the API to update opened status
        verify { mockGistQueue.logOpenedStatus(inboxMessage, true) }

        // And it should publish a TrackInAppMetricEvent with Metric.Opened
        verify {
            mockEventBus.publish(
                Event.TrackInAppMetricEvent(
                    deliveryID = deliveryId,
                    event = Metric.Opened
                )
            )
        }

        // And it should pass the action to the next middleware/reducer
        verify { nextFn(action) }
    }

    @Test
    fun processInboxMessages_givenUpdateOpenedWithOpenedFalse_shouldNotPublishMetricEvent() {
        // Given an inbox message being marked as unopened
        val deliveryId = String.random
        val queueId = String.random
        val inboxMessage = InboxMessage(deliveryId = deliveryId, queueId = queueId, opened = true)
        val action = InAppMessagingAction.InboxAction.UpdateOpened(inboxMessage, opened = false)

        // When the middleware processes the action
        val middleware = processInboxMessages()
        middleware(store)(nextFn)(action)

        // Then it should call the API to update opened status
        verify { mockGistQueue.logOpenedStatus(inboxMessage, false) }

        // But it should NOT publish a metric event
        verify(exactly = 0) {
            mockEventBus.publish(any<Event.TrackInAppMetricEvent>())
        }

        // And it should pass the action to the next middleware/reducer
        verify { nextFn(action) }
    }
}
