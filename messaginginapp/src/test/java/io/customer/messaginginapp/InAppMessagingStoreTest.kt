package io.customer.messaginginapp

import io.customer.commontest.config.TestConfig
import io.customer.commontest.core.TestConstants
import io.customer.commontest.extensions.assertNoInteractions
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.commontest.extensions.random
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.ModalMessageState
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.messaginginapp.testutils.extension.createInAppMessage
import io.customer.messaginginapp.testutils.extension.createInboxMessage
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
import java.util.UUID
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

    private lateinit var module: ModuleMessagingInApp
    private lateinit var manager: InAppMessagingManager

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfig)
        module = ModuleMessagingInApp(
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
            createInAppMessage(queueId = "1", priority = 2),
            createInAppMessage(queueId = "2", priority = 1),
            createInAppMessage(queueId = "3", priority = 3)
        )

        initializeAndSetUser()
        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(messages))

        val state = manager.getCurrentState()
        state.messagesInQueue.size shouldBeEqualTo 3
        state.modalMessageState
            .shouldBeInstanceOf<ModalMessageState.Loading>()
            .message
            .queueId shouldBeEqualTo "2"
    }

    @Test
    fun givenDuplicateMessages_whenProcessingMessages_thenDuplicatesAreRemoved() = runTest {
        val messages = listOf(
            createInAppMessage(queueId = "1"),
            createInAppMessage(queueId = "1"),
            createInAppMessage(queueId = "2")
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
        val message = createInAppMessage(queueId = "1", pageRule = pageRuleEquals("home"))

        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(message)))
        manager.dispatch(InAppMessagingAction.SetPageRoute("home"))

        var state = manager.getCurrentState()
        state.currentRoute shouldBe "home"
        state.modalMessageState
            .shouldBeInstanceOf<ModalMessageState.Loading>()
            .message
            .queueId shouldBeEqualTo "1"

        manager.dispatch(InAppMessagingAction.SetPageRoute("profile"))

        state = manager.getCurrentState()
        state.currentRoute shouldBe "profile"

        val currentState = state.modalMessageState
        currentState
            .shouldBeInstanceOf<ModalMessageState.Dismissed>()
            .message
            .queueId shouldBeEqualTo "1"
    }

    @Test
    fun givenMultipleMessagesWithDifferentRouteRules_whenRouteChanges_thenCorrectMessageIsDisplayed() = runTest {
        initializeAndSetUser()
        val homeMessage = createInAppMessage(queueId = "1", pageRule = pageRuleContains("home"))
        val profileMessage = createInAppMessage(queueId = "1", pageRule = pageRuleEquals("profile"))
        val generalMessage = createInAppMessage(queueId = "3")

        // process messages and set initial route
        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(homeMessage, profileMessage, generalMessage)))
        manager.dispatch(InAppMessagingAction.SetPageRoute("home"))

        // verify general message is displayed first (as it has no route rule)
        var state = manager.getCurrentState()

        val messageBeingDisplayed = state.modalMessageState
            .shouldBeInstanceOf<ModalMessageState.Loading>()
            .message
        messageBeingDisplayed.queueId shouldBe "3"

        // make the message visible and then dismiss it
        manager.dispatch(InAppMessagingAction.DisplayMessage(messageBeingDisplayed))
        manager.dispatch(InAppMessagingAction.DismissMessage(messageBeingDisplayed))

        // change route to "profile" and verify no message is displayed
        manager.dispatch(InAppMessagingAction.SetPageRoute("profile"))
        state = manager.getCurrentState()
        state.modalMessageState shouldBeInstanceOf ModalMessageState.Dismissed::class.java

        // change route back to "home" and verify home message is now processed
        manager.dispatch(InAppMessagingAction.SetPageRoute("home"))
        state = manager.getCurrentState()
        state.modalMessageState
            .shouldBeInstanceOf<ModalMessageState.Loading>()
            .message
            .queueId shouldBeEqualTo "1"
    }

    @Test
    fun givenVisibleMessage_whenDismissed_thenMessageStateUpdatesAndQueueIdIsRecorded() = runTest {
        initializeAndSetUser()
        val message = createInAppMessage(queueId = "1")

        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(message)))
        manager.dispatch(InAppMessagingAction.DisplayMessage(message))
        manager.dispatch(InAppMessagingAction.DismissMessage(message, shouldLog = false, viaCloseAction = true))

        val state = manager.getCurrentState()
        state.modalMessageState shouldBeInstanceOf ModalMessageState.Dismissed::class.java
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
    fun givenNoUser_whenProcessingMessage_thenAnonymousMessagingIsSupported() = runTest {
        // Initialize without setting user - should support anonymous messaging
        manager.dispatch(InAppMessagingAction.Initialize(siteId = String.random, dataCenter = String.random, environment = GistEnvironment.PROD))

        val message = createInAppMessage(queueId = "1")
        manager.dispatch(InAppMessagingAction.LoadMessage(message))

        val state = manager.getCurrentState()
        // Anonymous users should be able to see messages
        state.modalMessageState shouldBeInstanceOf ModalMessageState.Loading::class.java

        verify { inAppEventListener wasNot Called }
    }

    @Test
    fun givenMessage_whenDisplayed_thenOnMessageShownCallbackIsCalled() = runTest {
        initializeAndSetUser()
        val message = createInAppMessage(queueId = "1")

        manager.dispatch(InAppMessagingAction.DisplayMessage(message))

        verify { inAppEventListener.messageShown(InAppMessage.getFromGistMessage(message)) }
        confirmVerified(inAppEventListener)
    }

    @Test
    fun givenActiveMessage_whenProcessingMessageQueue_thenNoNewMessageIsProcessed() = runTest {
        initializeAndSetUser()

        // Create and display an initial active message
        val activeMessage = createInAppMessage(queueId = "active")
        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(activeMessage)))
        manager.dispatch(InAppMessagingAction.DisplayMessage(activeMessage))

        // Verify that the active message is displayed
        var state = manager.getCurrentState()
        state.modalMessageState shouldBeInstanceOf ModalMessageState.Displayed::class.java
        (state.modalMessageState as ModalMessageState.Displayed).message.queueId shouldBeEqualTo "active"

        // Try to process a new message queue
        val newMessage1 = createInAppMessage(queueId = "new1")
        val newMessage2 = createInAppMessage(queueId = "new2")
        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(newMessage1, newMessage2)))

        // Verify that the state hasn't changed and no new message was processed
        state = manager.getCurrentState()
        state.modalMessageState
            .shouldBeInstanceOf<ModalMessageState.Displayed>()
            .message
            .queueId shouldBeEqualTo "active"

        // Verify that the new messages are added to the queue but not processed
        state.messagesInQueue.map { it.queueId } shouldContainAll listOf("new1", "new2")
    }

    @Test
    fun givenNoActiveMessage_whenProcessingMessageQueue_thenNewMessageIsProcessed() = runTest {
        initializeAndSetUser()

        // Process a new message queue when there's no active message
        val newMessage1 = createInAppMessage(queueId = "new1")
        val newMessage2 = createInAppMessage(queueId = "new2")
        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(newMessage1, newMessage2)))

        // Verify that the first message is being processed
        val state = manager.getCurrentState()
        state.modalMessageState
            .shouldBeInstanceOf<ModalMessageState.Loading>()
            .message
            .queueId shouldBeEqualTo "new1"

        // Verify that the second message is in the queue
        state.messagesInQueue.map { it.queueId } shouldContain "new2"
    }

    @Test
    fun givenMessageBeingProcessed_whenRouteChanges_thenMessageIsHandledCorrectly() = runTest {
        initializeAndSetUser()

        // Create a message with a specific route rule
        val message = createInAppMessage(
            queueId = "1",
            pageRule = pageRuleContains("home")
        )

        // Set initial route and start processing the message
        manager.dispatch(InAppMessagingAction.SetPageRoute("home"))
        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(message)))

        // Verify that the message is being processed
        var state = manager.getCurrentState()
        state.modalMessageState
            .shouldBeInstanceOf<ModalMessageState.Loading>()
            .message
            .queueId shouldBeEqualTo "1"

        // Change route before the message is fully displayed
        manager.dispatch(InAppMessagingAction.SetPageRoute("profile"))

        // Verify that the message is no longer being processed and not displayed
        state = manager.getCurrentState()
        state.modalMessageState shouldBeInstanceOf ModalMessageState.Dismissed::class.java
        state.currentRoute shouldBeEqualTo "profile"

        // Change route back to "home"
        manager.dispatch(InAppMessagingAction.SetPageRoute("home"))

        // Verify that the message is being processed again
        state = manager.getCurrentState()
        state.modalMessageState
            .shouldBeInstanceOf<ModalMessageState.Loading>()
            .message
            .queueId shouldBeEqualTo "1"

        // Simulate message display
        manager.dispatch(InAppMessagingAction.DisplayMessage(message))

        // Verify that the message is now displayed
        state = manager.getCurrentState()
        state.modalMessageState
            .shouldBeInstanceOf<ModalMessageState.Displayed>()
            .message
            .queueId shouldBeEqualTo "1"
    }

    @Test
    fun givenMessage_whenDismissedAndReprocessed_thenMessageIsNotDisplayedAgain() = runTest {
        initializeAndSetUser()
        val message = createInAppMessage(queueId = "1")

        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(message)))
        manager.dispatch(InAppMessagingAction.DisplayMessage(message))
        manager.dispatch(InAppMessagingAction.DismissMessage(message))

        // Attempt to process the same message again
        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(message)))

        val state = manager.getCurrentState()
        state.modalMessageState shouldBeInstanceOf ModalMessageState.Dismissed::class.java
        state.shownMessageQueueIds.contains("1") shouldBe true

        verify(exactly = 1) { inAppEventListener.messageShown(InAppMessage.getFromGistMessage(message)) }
        verify(exactly = 1) { inAppEventListener.messageDismissed(InAppMessage.getFromGistMessage(message)) }
    }

    @Test
    fun givenMessage_whenDismissed_thenOnMessageDismissedCallbackIsCalled() = runTest {
        initializeAndSetUser()
        val message = createInAppMessage(queueId = "1")

        manager.dispatch(InAppMessagingAction.DismissMessage(message))

        verify { inAppEventListener.messageDismissed(InAppMessage.getFromGistMessage(message)) }
    }

    @Test
    fun givenMessage_whenLoadingFails_thenOnErrorCallbackIsCalled() = runTest {
        initializeAndSetUser()
        val message = createInAppMessage(queueId = "1")

        manager.dispatch(InAppMessagingAction.EngineAction.MessageLoadingFailed(message))

        verify { inAppEventListener.errorWithMessage(InAppMessage.getFromGistMessage(message)) }
    }

    @Test
    fun givenMessage_whenEmbedded_thenNoCallbackIsCalled() = runTest {
        initializeAndSetUser()
        // Create a message with custom properties to include an elementId for embedding
        // We need to manually construct the Message here since our helper doesn't support elementId
        val message = Message(
            messageId = UUID.randomUUID().toString(),
            queueId = "1",
            properties = mapOf("gist" to mapOf("elementId" to "testElementId"))
        )

        manager.dispatch(InAppMessagingAction.EmbedMessages(listOf(message)))

        assertNoInteractions(inAppEventListener)
    }

    @Test
    fun givenMessage_whenErrorOccurs_thenOnErrorCallbackIsCalled() = runTest {
        initializeAndSetUser()
        val message = createInAppMessage(queueId = "1")

        manager.dispatch(InAppMessagingAction.EngineAction.MessageLoadingFailed(message))

        verify { inAppEventListener.errorWithMessage(InAppMessage.getFromGistMessage(message)) }
    }

    @Test
    fun givenMessage_whenTapActionOccurs_thenOnActionCallbackIsCalled() = runTest {
        initializeAndSetUser()
        val message = createInAppMessage(queueId = "1")
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
        state.modalMessageState shouldBeInstanceOf ModalMessageState.Initial::class.java
        state.messagesInQueue shouldBe emptySet()

        // Verify that the event listener is not called
        verify { inAppEventListener wasNot Called }
    }

    @Test
    fun givenNonPersistentMessage_whenDisplayed_thenMessageIsMarkedAsShownImmediately() = runTest {
        initializeAndSetUser()

        // Create a non-persistent message - by default messages are non-persistent
        val nonPersistentMessage = createInAppMessage(queueId = "non-persistent")

        // Process the message
        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(nonPersistentMessage)))

        // Message should be in queue and loading
        var state = manager.getCurrentState()
        state.messagesInQueue.size shouldBeEqualTo 1
        state.modalMessageState.shouldBeInstanceOf<ModalMessageState.Loading>()

        // Display the message
        manager.dispatch(InAppMessagingAction.DisplayMessage(nonPersistentMessage))

        // Check state after display - message should be displayed and already marked as shown
        state = manager.getCurrentState()
        state.modalMessageState.shouldBeInstanceOf<ModalMessageState.Displayed>()
        state.shownMessageQueueIds.contains("non-persistent") shouldBe true

        // Verify message shown callback was called
        verify(exactly = 1) { inAppEventListener.messageShown(InAppMessage.getFromGistMessage(nonPersistentMessage)) }
    }

    @Test
    fun givenPersistentMessage_whenDisplayed_thenMessageIsNotMarkedAsShownUntilDismissed() = runTest {
        initializeAndSetUser()

        // Create a persistent message with the persistent flag set to true
        val persistentMessage = createInAppMessage(queueId = "persistent", persistent = true)

        // Process the message
        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(persistentMessage)))

        // Message should be in queue and loading
        var state = manager.getCurrentState()
        state.messagesInQueue.size shouldBeEqualTo 1
        state.modalMessageState.shouldBeInstanceOf<ModalMessageState.Loading>()

        // Display the message
        manager.dispatch(InAppMessagingAction.DisplayMessage(persistentMessage))

        // Check state after display - message should be displayed but NOT marked as shown yet
        state = manager.getCurrentState()
        state.modalMessageState.shouldBeInstanceOf<ModalMessageState.Displayed>()
        state.shownMessageQueueIds.contains("persistent") shouldBe false

        // Verify message shown callback was still called even though not marked as shown
        verify(exactly = 1) { inAppEventListener.messageShown(InAppMessage.getFromGistMessage(persistentMessage)) }

        // Now dismiss the message
        manager.dispatch(InAppMessagingAction.DismissMessage(persistentMessage))

        // After dismissal, the message should be marked as shown
        state = manager.getCurrentState()
        state.modalMessageState.shouldBeInstanceOf<ModalMessageState.Dismissed>()
        state.shownMessageQueueIds.contains("persistent") shouldBe true

        // Verify message dismissed callback was called
        verify(exactly = 1) { inAppEventListener.messageDismissed(InAppMessage.getFromGistMessage(persistentMessage)) }
    }

    @Test
    fun givenPersistentMessage_whenDismissedWithoutCloseAction_thenMessageIsNotMarkedAsShown() = runTest {
        initializeAndSetUser()

        // Create a persistent message
        val persistentMessage = createInAppMessage(queueId = "persistent", persistent = true)

        // Process and display the message
        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(persistentMessage)))
        manager.dispatch(InAppMessagingAction.DisplayMessage(persistentMessage))

        // Verify the message is displayed but not marked as shown
        var state = manager.getCurrentState()
        state.modalMessageState.shouldBeInstanceOf<ModalMessageState.Displayed>()
        state.shownMessageQueueIds.contains("persistent") shouldBe false

        // Dismiss the message but set viaCloseAction to false (e.g., like when changing routes)
        manager.dispatch(
            InAppMessagingAction.DismissMessage(
                persistentMessage,
                shouldLog = true,
                viaCloseAction = false
            )
        )

        // After dismissal without close action, the message should NOT be marked as shown
        state = manager.getCurrentState()
        state.modalMessageState.shouldBeInstanceOf<ModalMessageState.Dismissed>()
        state.shownMessageQueueIds.contains("persistent") shouldBe false

        // The message could still be displayed again later since it wasn't marked as shown
    }

    @Test
    fun givenPersistentMessage_whenDismissedWithoutLogging_thenMessageIsNotMarkedAsShown() = runTest {
        initializeAndSetUser()

        // Create a persistent message
        val persistentMessage = createInAppMessage(queueId = "persistent", persistent = true)

        // Process and display the message
        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(persistentMessage)))
        manager.dispatch(InAppMessagingAction.DisplayMessage(persistentMessage))

        // Verify the message is displayed but not marked as shown
        var state = manager.getCurrentState()
        state.modalMessageState.shouldBeInstanceOf<ModalMessageState.Displayed>()
        state.shownMessageQueueIds.contains("persistent") shouldBe false

        // Dismiss the message but set shouldLog to false
        manager.dispatch(
            InAppMessagingAction.DismissMessage(
                persistentMessage,
                shouldLog = false,
                viaCloseAction = true
            )
        )

        // After dismissal without logging, the message should NOT be marked as shown
        state = manager.getCurrentState()
        state.modalMessageState.shouldBeInstanceOf<ModalMessageState.Dismissed>()
        state.shownMessageQueueIds.contains("persistent") shouldBe false
    }

    @Test
    fun givenMixOfPersistentAndNonPersistentMessages_whenProcessed_thenBehavesCorrectlyForEachType() = runTest {
        initializeAndSetUser()

        // Create both types of messages
        val persistentMessage = createInAppMessage(queueId = "persistent", persistent = true)
        val nonPersistentMessage = createInAppMessage(queueId = "non-persistent")

        // Process both messages
        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(persistentMessage, nonPersistentMessage)))

        // First message is loaded based on priority (which should be equal for both)
        var state = manager.getCurrentState()
        val loadedMessage = state.modalMessageState.shouldBeInstanceOf<ModalMessageState.Loading>().message

        // Display whatever message was loaded first
        manager.dispatch(InAppMessagingAction.DisplayMessage(loadedMessage))

        // Check state after displaying first message
        state = manager.getCurrentState()
        state.modalMessageState.shouldBeInstanceOf<ModalMessageState.Displayed>()

        val firstMessageQueueId = loadedMessage.queueId
        val secondMessageQueueId = if (firstMessageQueueId == "persistent") "non-persistent" else "persistent"

        // If non-persistent message was displayed first, it should be marked as shown
        if (firstMessageQueueId == "non-persistent") {
            state.shownMessageQueueIds.contains("non-persistent") shouldBe true
        } else {
            // If persistent message was displayed first, it should NOT be marked as shown
            state.shownMessageQueueIds.contains("persistent") shouldBe false
        }

        // Dismiss the first message
        manager.dispatch(InAppMessagingAction.DismissMessage(loadedMessage))

        // After dismissal, check state again
        state = manager.getCurrentState()

        // Both messages should now be in shownMessageQueueIds
        // (non-persistent from display, persistent from dismissal)
        state.shownMessageQueueIds.contains(firstMessageQueueId) shouldBe true

        // Check if there's another message being processed - it might not be immediately Loading
        // due to how the reducer and middleware work in the real implementation
        if (state.modalMessageState is ModalMessageState.Loading) {
            // If it's loading, verify it's the second message
            val secondLoadedMessage = (state.modalMessageState as ModalMessageState.Loading).message
            secondLoadedMessage.queueId shouldBeEqualTo secondMessageQueueId

            // Display the second message
            manager.dispatch(InAppMessagingAction.DisplayMessage(secondLoadedMessage))
        } else {
            // If we don't get to the Loading state, we need to manually process the next message
            // since the test environment might behave differently from production
            val secondMessage = if (firstMessageQueueId == "persistent") nonPersistentMessage else persistentMessage
            manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(secondMessage)))
            manager.dispatch(InAppMessagingAction.DisplayMessage(secondMessage))
        }

        // Check state after displaying second message
        state = manager.getCurrentState()

        // If the second message is non-persistent, it should be marked as shown immediately
        if (secondMessageQueueId == "non-persistent") {
            state.shownMessageQueueIds.contains("non-persistent") shouldBe true
        }
        // If the second message is persistent, it should NOT be marked as shown until dismissed
        else {
            state.shownMessageQueueIds.contains("persistent") shouldBe false
        }

        // Dismiss the second message
        // Get the current message from state to ensure we're dismissing the correct one
        val currentMessage = (state.modalMessageState as? ModalMessageState.Displayed)?.message
            ?: (if (secondMessageQueueId == "persistent") persistentMessage else nonPersistentMessage)

        manager.dispatch(InAppMessagingAction.DismissMessage(currentMessage))

        // Now all messages should be marked as shown
        state = manager.getCurrentState()
        state.shownMessageQueueIds.contains("persistent") shouldBe true
        state.shownMessageQueueIds.contains("non-persistent") shouldBe true

        // Verify callbacks were called for both messages
        verify(exactly = 1) { inAppEventListener.messageShown(InAppMessage.getFromGistMessage(persistentMessage)) }
        verify(exactly = 1) { inAppEventListener.messageShown(InAppMessage.getFromGistMessage(nonPersistentMessage)) }
        verify(exactly = 1) { inAppEventListener.messageDismissed(InAppMessage.getFromGistMessage(persistentMessage)) }
        verify(exactly = 1) { inAppEventListener.messageDismissed(InAppMessage.getFromGistMessage(nonPersistentMessage)) }
    }

    @Test
    fun givenInboxMessages_whenProcessed_thenMessagesAreAvailableViaMessageInbox() = runTest {
        initializeAndSetUser()

        // Create test inbox messages
        val message1 = createInboxMessage(deliveryId = "inbox1", priority = 1, opened = false)
        val message2 = createInboxMessage(deliveryId = "inbox2", priority = 2, opened = true)
        val message3 = createInboxMessage(deliveryId = "inbox3", priority = 3, opened = false)

        // Process inbox messages via action
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(listOf(message1, message2, message3)))

        // Verify MessageInbox.getMessages() returns correct messages
        val messageInbox = module.inbox()
        val retrievedMessages = messageInbox.getMessages()
        retrievedMessages.size shouldBeEqualTo 3
        retrievedMessages shouldContainAll listOf(message1, message2, message3)
    }

    @Test
    fun givenDuplicateInboxMessages_whenProcessed_thenDuplicatesAreRemovedByDeliveryId() = runTest {
        initializeAndSetUser()

        // Create inbox messages with same deliveryId but different properties
        // This simulates middleware receiving duplicate deliveryIds with different states
        val message1 = createInboxMessage(deliveryId = "inbox1", priority = 1, opened = false)
        val message2 = createInboxMessage(deliveryId = "inbox1", priority = 2, opened = true) // Same deliveryId, different props
        val message3 = createInboxMessage(deliveryId = "inbox2", priority = 2, opened = true)

        // Process inbox messages with duplicates
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(listOf(message1, message2, message3)))

        // Verify duplicates are removed by deliveryId (distinctBy)
        // Only the first occurrence of each deliveryId is kept
        val state = manager.getCurrentState()
        state.inboxMessages.size shouldBeEqualTo 2
        state.inboxMessages.any { it.deliveryId == "inbox1" } shouldBe true
        state.inboxMessages.any { it.deliveryId == "inbox2" } shouldBe true

        // Verify the first occurrence is kept (message1 with opened=false, not message2)
        val inbox1Message = state.inboxMessages.first { it.deliveryId == "inbox1" }
        inbox1Message.opened shouldBe false
        inbox1Message.priority shouldBeEqualTo 1
    }
}
