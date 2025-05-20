package io.customer.messaginginapp.state

import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.random
import io.customer.messaginginapp.gist.data.model.GistProperties
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.MessagePosition
import io.customer.messaginginapp.testutils.core.JUnitTest
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InAppMessageReducerTest : JUnitTest() {

    private lateinit var initialState: InAppMessagingState

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfig)

        initialState = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random,
            pollInterval = 60000L,
            userId = String.random
        )
    }

    @Test
    fun displayMessage_whenNonPersistentMessage_expectMessageMarkedAsShown() {
        val testMessage = createTestMessage(persistent = false)

        // Initial state with the message in queue
        val startingState = initialState.copy(
            messagesInQueue = setOf(testMessage),
            shownMessageQueueIds = emptySet()
        )

        // When the message is displayed
        val displayAction = InAppMessagingAction.DisplayMessage(testMessage)
        val resultState = inAppMessagingReducer(startingState, displayAction)

        // Then the message should be added to shownMessageQueueIds
        assertTrue(resultState.shownMessageQueueIds.contains(testMessage.queueId!!))

        // And the message should be in Displayed state
        val modalState = resultState.modalMessageState as ModalMessageState.Displayed
        assertEquals(testMessage, modalState.message)

        // And the message should be removed from the queue
        assertTrue(resultState.messagesInQueue.isEmpty())
    }

    @Test
    fun displayMessage_whenPersistentMessage_expectMessageNotMarkedAsShown() {
        val testMessage = createTestMessage(persistent = true)

        // Initial state with the message in queue
        val startingState = initialState.copy(
            messagesInQueue = setOf(testMessage),
            shownMessageQueueIds = emptySet()
        )

        // When the message is displayed
        val displayAction = InAppMessagingAction.DisplayMessage(testMessage)
        val resultState = inAppMessagingReducer(startingState, displayAction)

        // Then the message should NOT be added to shownMessageQueueIds
        assertFalse(resultState.shownMessageQueueIds.contains(testMessage.queueId!!))

        // And the message should be in Displayed state
        val modalState = resultState.modalMessageState as ModalMessageState.Displayed
        assertEquals(testMessage, modalState.message)

        // And the message should be removed from the queue
        assertTrue(resultState.messagesInQueue.isEmpty())
    }

    @Test
    fun dismissMessage_whenNonPersistentMessage_expectNoChangeToShownMessageQueueIds() {
        val testMessage = createTestMessage(persistent = false)

        // The message is already displayed and in shownMessageQueueIds
        val startingState = initialState.copy(
            modalMessageState = ModalMessageState.Displayed(testMessage),
            shownMessageQueueIds = setOf(testMessage.queueId!!)
        )

        // When the message is dismissed
        val dismissAction = InAppMessagingAction.DismissMessage(testMessage)
        val resultState = inAppMessagingReducer(startingState, dismissAction)

        // Then the message should remain in shownMessageQueueIds (no new entries)
        assertEquals(1, resultState.shownMessageQueueIds.size)
        assertTrue(resultState.shownMessageQueueIds.contains(testMessage.queueId!!))

        // And the message state should be Dismissed
        val modalState = resultState.modalMessageState as ModalMessageState.Dismissed
        assertEquals(testMessage, modalState.message)
    }

    @Test
    fun dismissMessage_whenPersistentMessage_expectMessageAddedToShownMessageQueueIds() {
        val testMessage = createTestMessage(persistent = true)

        // The message is displayed but not in shownMessageQueueIds
        val startingState = initialState.copy(
            modalMessageState = ModalMessageState.Displayed(testMessage),
            shownMessageQueueIds = emptySet()
        )

        // When the message is dismissed via close action
        val dismissAction = InAppMessagingAction.DismissMessage(testMessage, shouldLog = true, viaCloseAction = true)
        val resultState = inAppMessagingReducer(startingState, dismissAction)

        // Then the message should be added to shownMessageQueueIds
        assertEquals(1, resultState.shownMessageQueueIds.size)
        assertTrue(resultState.shownMessageQueueIds.contains(testMessage.queueId!!))

        // And the message state should be Dismissed
        val modalState = resultState.modalMessageState as ModalMessageState.Dismissed
        assertEquals(testMessage, modalState.message)
    }

    @Test
    fun dismissMessage_whenPersistentMessageNotViaCloseAction_expectMessageNotAddedToShownMessageQueueIds() {
        val testMessage = createTestMessage(persistent = true)

        // The message is displayed but not in shownMessageQueueIds
        val startingState = initialState.copy(
            modalMessageState = ModalMessageState.Displayed(testMessage),
            shownMessageQueueIds = emptySet()
        )

        // When the message is dismissed NOT via close action
        val dismissAction = InAppMessagingAction.DismissMessage(testMessage, shouldLog = true, viaCloseAction = false)
        val resultState = inAppMessagingReducer(startingState, dismissAction)

        // Then the message should NOT be added to shownMessageQueueIds
        assertTrue(resultState.shownMessageQueueIds.isEmpty())

        // And the message state should be Dismissed
        val modalState = resultState.modalMessageState as ModalMessageState.Dismissed
        assertEquals(testMessage, modalState.message)
    }

    @Test
    fun dismissMessage_whenPersistentMessageWithLoggingDisabled_expectMessageNotAddedToShownMessageQueueIds() {
        // Given a persistent message that is displayed but not yet marked as shown
        val testMessage = createTestMessage(persistent = true)

        // The message is displayed but not in shownMessageQueueIds
        val startingState = initialState.copy(
            modalMessageState = ModalMessageState.Displayed(testMessage),
            shownMessageQueueIds = emptySet()
        )

        // When the message is dismissed via close action but logging is disabled
        val dismissAction = InAppMessagingAction.DismissMessage(testMessage, shouldLog = false, viaCloseAction = true)
        val resultState = inAppMessagingReducer(startingState, dismissAction)

        // Then the message should NOT be added to shownMessageQueueIds
        assertTrue(resultState.shownMessageQueueIds.isEmpty())

        // And the message state should be Dismissed
        val modalState = resultState.modalMessageState as ModalMessageState.Dismissed
        assertEquals(testMessage, modalState.message)
    }

    @Test
    fun shouldMarkMessageAsShown_whenDisplayingNonPersistentMessage_expectTrue() {
        val testMessage = createTestMessage(persistent = false)

        // When we check if the message should be marked as shown when displayed
        val displayAction = InAppMessagingAction.DisplayMessage(testMessage)
        val shouldMarkAsShown = displayAction.shouldMarkMessageAsShown()

        // Then it should return true
        assertTrue(shouldMarkAsShown)
    }

    @Test
    fun shouldMarkMessageAsShown_whenDisplayingPersistentMessage_expectFalse() {
        val testMessage = createTestMessage(persistent = true)

        // When we check if the message should be marked as shown when displayed
        val displayAction = InAppMessagingAction.DisplayMessage(testMessage)
        val shouldMarkAsShown = displayAction.shouldMarkMessageAsShown()

        // Then it should return false
        assertFalse(shouldMarkAsShown)
    }

    @Test
    fun shouldMarkMessageAsShown_whenDismissingPersistentMessageViaCloseAction_expectTrue() {
        val testMessage = createTestMessage(persistent = true)

        // When we check if the message should be marked as shown when dismissed via close action
        val dismissAction = InAppMessagingAction.DismissMessage(testMessage, shouldLog = true, viaCloseAction = true)
        val shouldMarkAsShown = dismissAction.shouldMarkMessageAsShown()

        // Then it should return true
        assertTrue(shouldMarkAsShown)
    }

    @Test
    fun shouldMarkMessageAsShown_whenDismissingPersistentMessageNotViaCloseAction_expectFalse() {
        val testMessage = createTestMessage(persistent = true)

        // When we check if the message should be marked as shown when dismissed NOT via close action
        val dismissAction = InAppMessagingAction.DismissMessage(testMessage, shouldLog = true, viaCloseAction = false)
        val shouldMarkAsShown = dismissAction.shouldMarkMessageAsShown()

        // Then it should return false
        assertFalse(shouldMarkAsShown)
    }

    @Test
    fun shouldMarkMessageAsShown_whenDismissingPersistentMessageWithLoggingDisabled_expectFalse() {
        val testMessage = createTestMessage(persistent = true)

        // When we check if the message should be marked as shown when dismissed with logging disabled
        val dismissAction = InAppMessagingAction.DismissMessage(testMessage, shouldLog = false, viaCloseAction = true)
        val shouldMarkAsShown = dismissAction.shouldMarkMessageAsShown()

        // Then it should return false
        assertFalse(shouldMarkAsShown)
    }

    @Test
    fun messageLoadingFailed_whenModalMessage_expectModalMessageToBeDismissed() {
        // Given a persistent message that is displayed but not yet marked as shown
        val testMessage = createTestMessage(persistent = false)

        // The message is displayed but not in shownMessageQueueIds
        val startingState = initialState.copy(
            modalMessageState = ModalMessageState.Displayed(testMessage),
            shownMessageQueueIds = emptySet()
        )

        // When the message is dismissed via close action but logging is disabled
        val dismissAction = InAppMessagingAction.EngineAction.MessageLoadingFailed(message = testMessage)
        val resultState = inAppMessagingReducer(startingState, dismissAction)

        // Then the message should NOT be added to shownMessageQueueIds
        assertTrue(resultState.shownMessageQueueIds.isEmpty())

        // And the message state should be Dismissed
        val modalState = resultState.modalMessageState as ModalMessageState.Dismissed
        assertEquals(testMessage, modalState.message)
    }

    @Test
    fun messageLoadingFailed_whenInlineMessage_expectInlineMessageToBeDismissed() {
        // Given a persistent message that is displayed but not yet marked as shown
        val elementId = String.random
        val testMessage = createTestMessage(persistent = false, elementId = elementId)

        // The message is displayed but not in shownMessageQueueIds
        val startingState = initialState.copy(
            queuedInlineMessagesState = QueuedInlineMessagesState()
                .addMessage(message = testMessage, elementId = elementId),
            shownMessageQueueIds = emptySet()
        )

        // When the message is dismissed via close action but logging is disabled
        val dismissAction = InAppMessagingAction.EngineAction.MessageLoadingFailed(message = testMessage)
        val resultState = inAppMessagingReducer(startingState, dismissAction)

        // Then the message should NOT be added to shownMessageQueueIds
        assertTrue(resultState.shownMessageQueueIds.isEmpty())

        // And the message state should be Dismissed
        val inlineMessageStateMap = resultState.queuedInlineMessagesState.messagesByElementId
        assertEquals(1, inlineMessageStateMap.count())
        val inlineMessageState = inlineMessageStateMap[elementId] as InlineMessageState.Dismissed
        assertEquals(testMessage, inlineMessageState.message)
    }

    @Test
    fun reset_shouldClearAllStates() {
        // Given a state with some messages
        val testMessage = createTestMessage(persistent = true)
        val startingState = initialState.copy(
            messagesInQueue = setOf(testMessage),
            shownMessageQueueIds = setOf(testMessage.queueId!!),
            modalMessageState = ModalMessageState.Displayed(testMessage)
        )

        // When reset action is dispatched
        val resetAction = InAppMessagingAction.Reset
        val resultState = inAppMessagingReducer(startingState, resetAction)

        // Then all message-related states should be cleared
        assertTrue(resultState.messagesInQueue.isEmpty())
        assertTrue(resultState.shownMessageQueueIds.isEmpty())
        assertTrue(resultState.modalMessageState is ModalMessageState.Initial)
    }

    /**
     * Helper method to create a test message with customizable persistence
     */
    private fun createTestMessage(persistent: Boolean, elementId: String? = null): Message {
        val messageId = String.random
        val queueId = String.random

        return mockk<Message>(relaxed = true) {
            every { this@mockk.messageId } returns messageId
            every { this@mockk.queueId } returns queueId
            every { this@mockk.gistProperties } returns GistProperties(
                routeRule = null,
                elementId = elementId,
                campaignId = null,
                position = MessagePosition.CENTER,
                persistent = persistent,
                overlayColor = null
            )
            every { this@mockk.isEmbedded } returns (elementId != null)
        }
    }
}
