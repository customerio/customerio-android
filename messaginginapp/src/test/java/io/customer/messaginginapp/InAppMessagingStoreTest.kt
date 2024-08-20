package io.customer.messaginginapp

import io.customer.commontest.core.RobolectricTest
import io.customer.commontest.extensions.random
import io.customer.messaginginapp.domain.InAppMessagingAction
import io.customer.messaginginapp.domain.InAppMessagingManager
import io.customer.messaginginapp.domain.MessageState
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.model.Message
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InAppMessagingStoreTest : RobolectricTest() {

    private val manager = InAppMessagingManager

    // Helper function to set up the initial state for tests
    private fun initializeAndSetUser() {
        manager.dispatch(InAppMessagingAction.Initialize(siteId = String.random, dataCenter = String.random, context = applicationMock, environment = GistEnvironment.PROD))
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
        (state.currentMessageState as? MessageState.Processing)?.message?.queueId shouldBeEqualTo "2"
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
        val message = Message(queueId = "1", properties = mapOf("gist" to mapOf("routeRuleAndroid" to "home")))

        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(message)))
        manager.dispatch(InAppMessagingAction.NavigateToRoute("home"))

        var state = manager.getCurrentState()
        state.currentRoute shouldBe "home"
        (state.currentMessageState as? MessageState.Processing)?.message?.queueId shouldBe "1"

        manager.dispatch(InAppMessagingAction.NavigateToRoute("profile"))

        state = manager.getCurrentState()
        state.currentRoute shouldBe "profile"

        val currentState = state.currentMessageState
        currentState shouldBeInstanceOf MessageState.Dismissed::class.java
        (currentState as MessageState.Dismissed).message.queueId shouldBe "1"
    }

    @Test
    fun givenMultipleMessagesWithDifferentRouteRules_whenRouteChanges_thenCorrectMessageIsDisplayed() = runTest {
        initializeAndSetUser()
        val homeMessage = Message(queueId = "1", properties = mapOf("gist" to mapOf("routeRuleAndroid" to "home")))
        val profileMessage = Message(queueId = "1", properties = mapOf("gist" to mapOf("routeRuleAndroid" to "profile")))
        val generalMessage = Message(queueId = "3")

        // process messages and set initial route
        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(homeMessage, profileMessage, generalMessage)))
        manager.dispatch(InAppMessagingAction.NavigateToRoute("home"))

        // verify general message is displayed first (as it has no route rule)
        var state = manager.getCurrentState()
        val messageBeingDisplayed = (state.currentMessageState as? MessageState.Processing)?.message
        messageBeingDisplayed?.queueId shouldBe "3"

        // make the message visible and then dismiss it
        manager.dispatch(InAppMessagingAction.DisplayMessage(messageBeingDisplayed!!))
        manager.dispatch(InAppMessagingAction.DismissMessage(messageBeingDisplayed))

        // change route to "profile" and verify no message is displayed
        manager.dispatch(InAppMessagingAction.NavigateToRoute("profile"))
        state = manager.getCurrentState()
        state.currentMessageState shouldBeInstanceOf MessageState.Dismissed::class.java

        // change route back to "home" and verify home message is now processed
        manager.dispatch(InAppMessagingAction.NavigateToRoute("home"))
        state = manager.getCurrentState()
        (state.currentMessageState as? MessageState.Processing)?.message?.queueId shouldBe "1"
    }

    @Test
    fun givenVisibleMessage_whenDismissed_thenMessageStateUpdatesAndQueueIdIsRecorded() = runTest {
        initializeAndSetUser()
        val message = Message(queueId = "1")

        manager.dispatch(InAppMessagingAction.ProcessMessageQueue(listOf(message)))
        manager.dispatch(InAppMessagingAction.DisplayMessage(message))
        manager.dispatch(InAppMessagingAction.DismissMessage(message, shouldLog = true, viaCloseAction = true))

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
}
