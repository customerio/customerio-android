package io.customer.messaginginapp.state

import io.customer.commontest.extensions.random
import io.customer.messaginginapp.gist.data.listeners.GistQueue
import io.customer.messaginginapp.gist.presentation.GistListener
import io.customer.messaginginapp.state.MessageBuilderTest.createMessage
import io.customer.messaginginapp.testutils.core.JUnitTest
import io.customer.messaginginapp.testutils.extension.createInAppMessage
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.reduxkotlin.Store

/**
 * Tests for the middleware functions in InAppMessagingMiddlewares.kt,
 * focused on behaviors related to inline in-app messaging.
 */
class InAppMessagingMiddlewaresTest : JUnitTest() {

    private lateinit var store: Store<InAppMessagingState>
    private lateinit var nextFn: (Any) -> Any
    private lateinit var mockGistQueue: GistQueue
    private lateinit var mockGistListener: GistListener
    private lateinit var mockLogger: Logger

    @BeforeEach
    fun setupMocks() {
        store = mockk(relaxed = true)
        nextFn = mockk(relaxed = true)
        mockGistQueue = mockk(relaxed = true)
        mockGistListener = mockk(relaxed = true)
        mockLogger = mockk(relaxed = true)

        val emptyState = InAppMessagingState()
        every { store.state } returns emptyState

        SDKComponent.overrideDependency<GistQueue>(mockGistQueue)
        SDKComponent.overrideDependency<Logger>(mockLogger)
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

        val middleware = testProcessMessages()
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

        val middleware = testRouteChangeMiddleware()
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

        val middleware = testRouteChangeMiddleware()
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

        val middleware = testProcessMessages()
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
    fun userChangeMiddleware_shouldBlockActionsWhenUserNotSet() {
        val state = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random,
            userId = null
        )
        every { store.state } returns state

        val middleware = userChangeMiddleware()
        val action = InAppMessagingAction.ProcessMessageQueue(emptyList())

        val dispatchedActions = mutableListOf<InAppMessagingAction>()
        every { store.dispatch(capture(slot())) } answers {
            val capturedAction = firstArg<InAppMessagingAction>()
            dispatchedActions.add(capturedAction)
            Unit
        }

        middleware(store)(nextFn)(action)

        verify { nextFn(ofType<InAppMessagingAction.ReportError>()) }
        verify(exactly = 0) { nextFn(action) }
    }

    @Test
    fun userChangeMiddleware_shouldAllowInitializeAction() {
        val state = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random,
            userId = null
        )
        every { store.state } returns state

        val middleware = userChangeMiddleware()
        val action = InAppMessagingAction.Initialize("site-id", "data-center", io.customer.messaginginapp.gist.GistEnvironment.PROD)
        middleware(store)(nextFn)(action)

        verify { nextFn(action) }
    }

    @Test
    fun displayModalMessageMiddleware_shouldIgnoreInlineMessages() {
        val elementId = "test-element"
        val inlineMessage = createMessage(elementId = elementId)

        val state = InAppMessagingState(
            modalMessageState = ModalMessageState.Initial
        )
        every { store.state } returns state

        val middleware = testDisplayModalMessageMiddleware()
        val action = InAppMessagingAction.LoadMessage(inlineMessage)

        middleware(store)(nextFn)(action)

        verify(exactly = 1) { nextFn(action) }
    }

    @Test
    fun middlewareChain_shouldExecuteInCorrectOrder() {
        val state = InAppMessagingState(userId = null)
        every { store.state } returns state

        val userMiddleware = userChangeMiddleware()
        val action = InAppMessagingAction.ProcessMessageQueue(emptyList())

        userMiddleware(store)(nextFn)(action)
        verify { nextFn(ofType<InAppMessagingAction.ReportError>()) }

        val stateWithUser = state.copy(userId = "test-user")
        every { store.state } returns stateWithUser

        val dispatchedActions = mutableListOf<InAppMessagingAction>()
        every { store.dispatch(capture(slot())) } answers {
            val capturedAction = firstArg<InAppMessagingAction>()
            dispatchedActions.add(capturedAction)
            Unit
        }

        val routeMiddleware = testRouteChangeMiddleware()
        val routeAction = InAppMessagingAction.SetPageRoute("new/route")

        routeMiddleware(store)(nextFn)(routeAction)

        assert(dispatchedActions.any { it is InAppMessagingAction.ProcessMessageQueue }) {
            "Expected ProcessMessageQueue action to be dispatched"
        }

        val inlineMessage = createMessage(elementId = "test-element")
        val messagesInQueue = setOf(inlineMessage)
        val stateWithMessages = stateWithUser.copy(
            messagesInQueue = messagesInQueue,
            currentRoute = "test/route"
        )
        every { store.state } returns stateWithMessages

        dispatchedActions.clear()

        val processMiddleware = testProcessMessages()
        val processAction = InAppMessagingAction.ProcessMessageQueue(messagesInQueue.toList())

        processMiddleware(store)(nextFn)(processAction)

        assert(dispatchedActions.any { it is InAppMessagingAction.EmbedMessages }) {
            "Expected EmbedMessages action to be dispatched"
        }
    }
}
