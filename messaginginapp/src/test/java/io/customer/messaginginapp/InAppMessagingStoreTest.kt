package io.customer.messaginginapp

import io.customer.commontest.config.TestConfig
import io.customer.commontest.core.TestConstants
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.commontest.extensions.random
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.MessageState
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.messaginginapp.testutils.extension.pageRuleContains
import io.customer.messaginginapp.testutils.extension.pageRuleEquals
import io.customer.messaginginapp.type.InAppEventListener
import io.customer.messaginginapp.type.InAppMessage
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.model.Region
import io.mockk.Called
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldContainAll
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class InAppMessagingStoreTest : IntegrationTest() {

    private var inAppEventListener = mockk<InAppEventListener>(relaxed = true)

    private lateinit var manager: InAppMessagingManager

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfig)
        ModuleMessagingInApp(
            config = MessagingInAppModuleConfig.Builder(
                siteId = TestConstants.Keys.SITE_ID,
                region = Region.US
            ).setEventListener(inAppEventListener).build()
        ).attachToSDKComponent()
        manager = SDKComponent.inAppMessagingManager
    }

    // Helper function to set up the initial state for tests
    private fun initializeAndSetUser() {
        manager.dispatch(InAppMessagingAction.Initialize(siteId = String.random, dataCenter = String.random, environment = GistEnvironment.PROD))
        manager.dispatch(InAppMessagingAction.SetUserIdentifier(String.random))
    }

    override fun teardown() {
        manager.dispatch(InAppMessagingAction.Reset)
        super.teardown()
    }

    @Test
    fun givenMessagesWithDifferentPriorities_whenProcessingMessages_thenHighestPriorityMessageIsProcessedFirst() = runTest {
        val messages = listOf(
            Message(queueId = "1", priority = 2),
            Message(queueId = "2", priority = 1),
            Message(queueId = "3", priority = 3)
        )

        initializeAndSetUser()
        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(messages))

        val state = manager.getCurrentState()
        state.messagesInQueue.size shouldBeEqualTo 3
        state.currentMessageState
            .shouldBeInstanceOf<MessageState.Loading>()
            .message
            .queueId shouldBeEqualTo "2"
    }

    @Test
    fun givenDuplicateMessages_whenProcessingMessages_thenDuplicatesAreRemoved() = runTest {
        val messages = listOf(
            Message(queueId = "1"),
            Message(queueId = "1"),
            Message(queueId = "2")
        )

        initializeAndSetUser()
        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(messages))

        val state = manager.getCurrentState()
        state.messagesInQueue.size shouldBeEqualTo 2
        state.messagesInQueue.any { it.queueId == "1" } shouldBe true
        state.messagesInQueue.any { it.queueId == "2" } shouldBe true
    }

    @Test
    fun givenMessageWithSpecificRouteRule_whenRouteChanges_thenMessageStateUpdatesAccordingly() = runTest {
        initializeAndSetUser()
        val message = Message(queueId = "1", properties = mapOf("gist" to mapOf("routeRuleAndroid" to pageRuleEquals("home"))))

        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(message)))
        manager.dispatch(InAppMessagingAction.SetPageRoute("home"))

        var state = manager.getCurrentState()
        state.currentRoute shouldBe "home"
        state.currentMessageState
            .shouldBeInstanceOf<MessageState.Loading>()
            .message
            .queueId shouldBeEqualTo "1"

        manager.dispatch(InAppMessagingAction.SetPageRoute("profile"))

        state = manager.getCurrentState()
        state.currentRoute shouldBe "profile"

        val currentState = state.currentMessageState
        currentState
            .shouldBeInstanceOf<MessageState.Dismissed>()
            .message
            .queueId shouldBeEqualTo "1"
    }

    @Test
    fun givenMultipleMessagesWithDifferentRouteRules_whenRouteChanges_thenCorrectMessageIsDisplayed() = runTest {
        initializeAndSetUser()
        val homeMessage = Message(queueId = "1", properties = mapOf("gist" to mapOf("routeRuleAndroid" to pageRuleContains("home"))))
        val profileMessage = Message(queueId = "1", properties = mapOf("gist" to mapOf("routeRuleAndroid" to pageRuleEquals("profile"))))
        val generalMessage = Message(queueId = "3")

        // process messages and set initial route
        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(homeMessage, profileMessage, generalMessage)))
        manager.dispatch(InAppMessagingAction.SetPageRoute("home"))

        // verify general message is displayed first (as it has no route rule)
        var state = manager.getCurrentState()

        val messageBeingDisplayed = state.currentMessageState
            .shouldBeInstanceOf<MessageState.Loading>()
            .message
        messageBeingDisplayed.queueId shouldBe "3"

        // make the message visible and then dismiss it
        manager.dispatch(InAppMessagingAction.DisplayMessage(messageBeingDisplayed))
        manager.dispatch(InAppMessagingAction.DismissMessage(messageBeingDisplayed))

        // change route to "profile" and verify no message is displayed
        manager.dispatch(InAppMessagingAction.SetPageRoute("profile"))
        state = manager.getCurrentState()
        state.currentMessageState shouldBeInstanceOf MessageState.Dismissed::class.java

        // change route back to "home" and verify home message is now processed
        manager.dispatch(InAppMessagingAction.SetPageRoute("home"))
        state = manager.getCurrentState()
        state.currentMessageState
            .shouldBeInstanceOf<MessageState.Loading>()
            .message
            .queueId shouldBeEqualTo "1"
    }

    @Test
    fun givenVisibleMessage_whenDismissed_thenMessageStateUpdatesAndQueueIdIsRecorded() = runTest {
        initializeAndSetUser()
        val message = Message(queueId = "1")

        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(message)))
        manager.dispatch(InAppMessagingAction.DisplayMessage(message))
        manager.dispatch(InAppMessagingAction.DismissMessage(message, shouldLog = false, viaCloseAction = true))

        val state = manager.getCurrentState()
        state.currentMessageState shouldBeInstanceOf MessageState.Dismissed::class.java
        state.shownMessageQueueIds.contains("1") shouldBe true
    }

    @Test
    fun givenInitialPollingInterval_whenIntervalIsChanged_thenNewIntervalIsReflectedInState() = runTest {
        initializeAndSetUser()

        manager.dispatch(InAppMessagingAction.SetPollingInterval(300_000L))

        val state = manager.getCurrentState()
        state.pollInterval shouldBeEqualTo 300_000L
    }

    @Test
    fun givenNoUser_whenProcessingMessage_thenNoActionIsTaken() = runTest {
        // Initialize without setting user
        manager.dispatch(InAppMessagingAction.Initialize(siteId = String.random, dataCenter = String.random, environment = GistEnvironment.PROD))

        val message = Message(queueId = "1")
        manager.dispatch(InAppMessagingAction.LoadMessage(message))

        val state = manager.getCurrentState()
        state.currentMessageState shouldBeInstanceOf MessageState.Initial::class.java

        verify { inAppEventListener wasNot Called }
    }

    @Test
    fun givenMessage_whenDisplayed_thenOnMessageShownCallbackIsCalled() = runTest {
        initializeAndSetUser()
        val message = Message(queueId = "1")

        manager.dispatch(InAppMessagingAction.DisplayMessage(message))

        verify { inAppEventListener.messageShown(InAppMessage.getFromGistMessage(message)) }
        confirmVerified(inAppEventListener)
    }

    @Test
    fun givenActiveMessage_whenProcessingMessageQueue_thenNoNewMessageIsProcessed() = runTest {
        initializeAndSetUser()

        // Create and display an initial active message
        val activeMessage = Message(queueId = "active")
        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(activeMessage)))
        manager.dispatch(InAppMessagingAction.DisplayMessage(activeMessage))

        // Verify that the active message is displayed
        var state = manager.getCurrentState()
        state.currentMessageState shouldBeInstanceOf MessageState.Displayed::class.java
        (state.currentMessageState as MessageState.Displayed).message.queueId shouldBeEqualTo "active"

        // Try to process a new message queue
        val newMessage1 = Message(queueId = "new1")
        val newMessage2 = Message(queueId = "new2")
        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(newMessage1, newMessage2)))

        // Verify that the state hasn't changed and no new message was processed
        state = manager.getCurrentState()
        state.currentMessageState
            .shouldBeInstanceOf<MessageState.Displayed>()
            .message
            .queueId shouldBeEqualTo "active"

        // Verify that the new messages are added to the queue but not processed
        state.messagesInQueue.map { it.queueId } shouldContainAll listOf("new1", "new2")
    }

    @Test
    fun givenNoActiveMessage_whenProcessingMessageQueue_thenNewMessageIsProcessed() = runTest {
        initializeAndSetUser()

        // Process a new message queue when there's no active message
        val newMessage1 = Message(queueId = "new1")
        val newMessage2 = Message(queueId = "new2")
        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(newMessage1, newMessage2)))

        // Verify that the first message is being processed
        val state = manager.getCurrentState()
        state.currentMessageState
            .shouldBeInstanceOf<MessageState.Loading>()
            .message
            .queueId shouldBeEqualTo "new1"

        // Verify that the second message is in the queue
        state.messagesInQueue.map { it.queueId } shouldContain "new2"
    }

    @Test
    fun givenMessageBeingProcessed_whenRouteChanges_thenMessageIsHandledCorrectly() = runTest {
        initializeAndSetUser()

        // Create a message with a specific route rule
        val message = Message(
            queueId = "1",
            properties = mapOf("gist" to mapOf("routeRuleAndroid" to pageRuleContains("home")))
        )

        // Set initial route and start processing the message
        manager.dispatch(InAppMessagingAction.SetPageRoute("home"))
        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(message)))

        // Verify that the message is being processed
        var state = manager.getCurrentState()
        state.currentMessageState
            .shouldBeInstanceOf<MessageState.Loading>()
            .message
            .queueId shouldBeEqualTo "1"

        // Change route before the message is fully displayed
        manager.dispatch(InAppMessagingAction.SetPageRoute("profile"))

        // Verify that the message is no longer being processed and not displayed
        state = manager.getCurrentState()
        state.currentMessageState shouldBeInstanceOf MessageState.Dismissed::class.java
        state.currentRoute shouldBeEqualTo "profile"

        // Change route back to "home"
        manager.dispatch(InAppMessagingAction.SetPageRoute("home"))

        // Verify that the message is being processed again
        state = manager.getCurrentState()
        state.currentMessageState
            .shouldBeInstanceOf<MessageState.Loading>()
            .message
            .queueId shouldBeEqualTo "1"

        // Simulate message display
        manager.dispatch(InAppMessagingAction.DisplayMessage(message))

        // Verify that the message is now displayed
        state = manager.getCurrentState()
        state.currentMessageState
            .shouldBeInstanceOf<MessageState.Displayed>()
            .message
            .queueId shouldBeEqualTo "1"
    }

    @Test
    fun givenMessage_whenDismissedAndReprocessed_thenMessageIsNotDisplayedAgain() = runTest {
        initializeAndSetUser()
        val message = Message(queueId = "1")

        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(message)))
        manager.dispatch(InAppMessagingAction.DisplayMessage(message))
        manager.dispatch(InAppMessagingAction.DismissMessage(message))

        // Attempt to process the same message again
        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(message)))

        val state = manager.getCurrentState()
        state.currentMessageState shouldBeInstanceOf MessageState.Dismissed::class.java
        state.shownMessageQueueIds.contains("1") shouldBe true

        verify(exactly = 1) { inAppEventListener.messageShown(InAppMessage.getFromGistMessage(message)) }
        verify(exactly = 1) { inAppEventListener.messageDismissed(InAppMessage.getFromGistMessage(message)) }
    }

    @Test
    fun givenMessage_whenDismissed_thenOnMessageDismissedCallbackIsCalled() = runTest {
        initializeAndSetUser()
        val message = Message(queueId = "1")

        manager.dispatch(InAppMessagingAction.DismissMessage(message))

        verify { inAppEventListener.messageDismissed(InAppMessage.getFromGistMessage(message)) }
    }

    @Test
    fun givenMessage_whenLoadingFails_thenOnErrorCallbackIsCalled() = runTest {
        initializeAndSetUser()
        val message = Message(queueId = "1")

        manager.dispatch(InAppMessagingAction.EngineAction.MessageLoadingFailed(message))

        verify { inAppEventListener.errorWithMessage(InAppMessage.getFromGistMessage(message)) }
    }

    @Test
    fun givenMessage_whenEmbedded_thenEmbedMessageCallbackIsCalled() = runTest {
        initializeAndSetUser()
        val message = Message(queueId = "1")
        val elementId = "testElementId"

        manager.dispatch(InAppMessagingAction.EmbedMessage(message, elementId))

        verify { inAppEventListener.messageShown(InAppMessage.getFromGistMessage(message)) }
    }

    @Test
    fun givenMessage_whenErrorOccurs_thenOnErrorCallbackIsCalled() = runTest {
        initializeAndSetUser()
        val message = Message(queueId = "1")

        manager.dispatch(InAppMessagingAction.EngineAction.MessageLoadingFailed(message))

        verify { inAppEventListener.errorWithMessage(InAppMessage.getFromGistMessage(message)) }
    }

    @Test
    fun givenMessage_whenTapActionOccurs_thenOnActionCallbackIsCalled() = runTest {
        initializeAndSetUser()
        val message = Message(queueId = "1")
        val route = "testRoute"
        val action = "testAction"
        val name = "testName"

        manager.dispatch(InAppMessagingAction.EngineAction.Tap(message, route, name, action))

        verify { inAppEventListener.messageActionTaken(InAppMessage.getFromGistMessage(message), action, name) }
    }

    @Test
    fun givenNoUser_whenRouteChanges_thenRouteIsUpdatedInState() = runTest {
        // Initialize without setting user
        manager.dispatch(InAppMessagingAction.Initialize(siteId = String.random, dataCenter = String.random, environment = GistEnvironment.PROD))

        // verify initial state
        var state = manager.getCurrentState()
        state.userId shouldBe null
        state.currentRoute shouldBe null

        // change route without setting user
        val newRoute = "home"
        manager.dispatch(InAppMessagingAction.SetPageRoute(newRoute))

        // verify that the route is updated even without a user
        state = manager.getCurrentState()
        state.userId shouldBe null
        state.currentRoute shouldBeEqualTo newRoute

        // Verify that no messages are processed
        state.currentMessageState shouldBeInstanceOf MessageState.Initial::class.java
        state.messagesInQueue shouldBe emptySet()

        // Verify that the event listener is not called
        verify { inAppEventListener wasNot Called }
    }
}
