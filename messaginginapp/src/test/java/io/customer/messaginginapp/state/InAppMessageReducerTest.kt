package io.customer.messaginginapp.state

import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.random
import io.customer.messaginginapp.gist.data.model.GistProperties
import io.customer.messaginginapp.gist.data.model.InboxMessage
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.MessagePosition
import io.customer.messaginginapp.testutils.core.JUnitTest
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
    fun displayMessage_givenNonPersistentMessage_expectMessageMarkedAsShown() {
        val testMessage = createTestMessage(persistent = false)

        val startingState = initialState.copy(
            messagesInQueue = setOf(testMessage),
            shownMessageQueueIds = emptySet()
        )

        val displayAction = InAppMessagingAction.DisplayMessage(testMessage)
        val resultState = inAppMessagingReducer(startingState, displayAction)

        assertTrue(resultState.shownMessageQueueIds.contains(testMessage.queueId!!))

        val modalState = resultState.modalMessageState as ModalMessageState.Displayed
        assertEquals(testMessage, modalState.message)

        assertTrue(resultState.messagesInQueue.isEmpty())
    }

    @Test
    fun displayMessage_givenPersistentMessage_expectMessageNotMarkedAsShown() {
        val testMessage = createTestMessage(persistent = true)

        val startingState = initialState.copy(
            messagesInQueue = setOf(testMessage),
            shownMessageQueueIds = emptySet()
        )

        val displayAction = InAppMessagingAction.DisplayMessage(testMessage)
        val resultState = inAppMessagingReducer(startingState, displayAction)

        assertFalse(resultState.shownMessageQueueIds.contains(testMessage.queueId!!))

        val modalState = resultState.modalMessageState as ModalMessageState.Displayed
        assertEquals(testMessage, modalState.message)

        assertTrue(resultState.messagesInQueue.isEmpty())
    }

    @Test
    fun dismissMessage_givenNonPersistentMessage_expectNoChangeToShownMessageQueueIds() {
        val testMessage = createTestMessage(persistent = false)

        val startingState = initialState.copy(
            modalMessageState = ModalMessageState.Displayed(testMessage),
            shownMessageQueueIds = setOf(testMessage.queueId!!)
        )

        val dismissAction = InAppMessagingAction.DismissMessage(testMessage)
        val resultState = inAppMessagingReducer(startingState, dismissAction)

        assertEquals(1, resultState.shownMessageQueueIds.size)
        assertTrue(resultState.shownMessageQueueIds.contains(testMessage.queueId!!))

        val modalState = resultState.modalMessageState as ModalMessageState.Dismissed
        assertEquals(testMessage, modalState.message)
    }

    @Test
    fun dismissMessage_givenPersistentMessage_expectMessageAddedToShownMessageQueueIds() {
        val testMessage = createTestMessage(persistent = true)

        val startingState = initialState.copy(
            modalMessageState = ModalMessageState.Displayed(testMessage),
            shownMessageQueueIds = emptySet()
        )

        val dismissAction = InAppMessagingAction.DismissMessage(testMessage, shouldLog = true, viaCloseAction = true)
        val resultState = inAppMessagingReducer(startingState, dismissAction)

        assertEquals(1, resultState.shownMessageQueueIds.size)
        assertTrue(resultState.shownMessageQueueIds.contains(testMessage.queueId!!))

        val modalState = resultState.modalMessageState as ModalMessageState.Dismissed
        assertEquals(testMessage, modalState.message)
    }

    @Test
    fun dismissMessage_givenPersistentMessageNotViaCloseAction_expectMessageNotAddedToShownMessageQueueIds() {
        val testMessage = createTestMessage(persistent = true)

        val startingState = initialState.copy(
            modalMessageState = ModalMessageState.Displayed(testMessage),
            shownMessageQueueIds = emptySet()
        )

        val dismissAction = InAppMessagingAction.DismissMessage(testMessage, shouldLog = true, viaCloseAction = false)
        val resultState = inAppMessagingReducer(startingState, dismissAction)

        assertTrue(resultState.shownMessageQueueIds.isEmpty())

        val modalState = resultState.modalMessageState as ModalMessageState.Dismissed
        assertEquals(testMessage, modalState.message)
    }

    @Test
    fun dismissMessage_givenPersistentMessageWithLoggingDisabled_expectMessageNotAddedToShownMessageQueueIds() {
        val testMessage = createTestMessage(persistent = true)

        val startingState = initialState.copy(
            modalMessageState = ModalMessageState.Displayed(testMessage),
            shownMessageQueueIds = emptySet()
        )

        val dismissAction = InAppMessagingAction.DismissMessage(testMessage, shouldLog = false, viaCloseAction = true)
        val resultState = inAppMessagingReducer(startingState, dismissAction)

        assertTrue(resultState.shownMessageQueueIds.isEmpty())

        val modalState = resultState.modalMessageState as ModalMessageState.Dismissed
        assertEquals(testMessage, modalState.message)
    }

    @Test
    fun shouldMarkMessageAsShown_givenDisplayingNonPersistentMessage_expectTrue() {
        val testMessage = createTestMessage(persistent = false)

        val displayAction = InAppMessagingAction.DisplayMessage(testMessage)
        val shouldMarkAsShown = displayAction.shouldMarkMessageAsShown()

        assertTrue(shouldMarkAsShown)
    }

    @Test
    fun shouldMarkMessageAsShown_givenDisplayingPersistentMessage_expectFalse() {
        val testMessage = createTestMessage(persistent = true)

        val displayAction = InAppMessagingAction.DisplayMessage(testMessage)
        val shouldMarkAsShown = displayAction.shouldMarkMessageAsShown()

        assertFalse(shouldMarkAsShown)
    }

    @Test
    fun shouldMarkMessageAsShown_givenDismissingPersistentMessageViaCloseAction_expectTrue() {
        val testMessage = createTestMessage(persistent = true)

        val dismissAction = InAppMessagingAction.DismissMessage(testMessage, shouldLog = true, viaCloseAction = true)
        val shouldMarkAsShown = dismissAction.shouldMarkMessageAsShown()

        assertTrue(shouldMarkAsShown)
    }

    @Test
    fun shouldMarkMessageAsShown_givenDismissingPersistentMessageNotViaCloseAction_expectFalse() {
        val testMessage = createTestMessage(persistent = true)

        val dismissAction = InAppMessagingAction.DismissMessage(testMessage, shouldLog = true, viaCloseAction = false)
        val shouldMarkAsShown = dismissAction.shouldMarkMessageAsShown()

        assertFalse(shouldMarkAsShown)
    }

    @Test
    fun shouldMarkMessageAsShown_givenDismissingPersistentMessageWithLoggingDisabled_expectFalse() {
        val testMessage = createTestMessage(persistent = true)

        val dismissAction = InAppMessagingAction.DismissMessage(testMessage, shouldLog = false, viaCloseAction = true)
        val shouldMarkAsShown = dismissAction.shouldMarkMessageAsShown()

        assertFalse(shouldMarkAsShown)
    }

    @Test
    fun messageDismissed_givenInlineMessageWithoutQueueId_expectStateUnchanged() {
        val elementId = String.random
        val testMessage = createTestMessage(persistent = false, queueId = null, elementId = elementId)

        val startingState = initialState.copy(
            queuedInlineMessagesState = QueuedInlineMessagesState()
                .addMessage(message = testMessage, elementId = elementId),
            shownMessageQueueIds = emptySet()
        )

        val dismissAction = InAppMessagingAction.DismissMessage(message = testMessage)
        val resultState = inAppMessagingReducer(startingState, dismissAction)

        assertEquals(startingState, resultState)
    }

    @Test
    fun messageLoadingFailed_givenModalMessage_expectModalMessageToBeDismissed() {
        val testMessage = createTestMessage(persistent = false)

        val startingState = initialState.copy(
            modalMessageState = ModalMessageState.Displayed(testMessage),
            shownMessageQueueIds = emptySet()
        )

        val dismissAction = InAppMessagingAction.EngineAction.MessageLoadingFailed(message = testMessage)
        val resultState = inAppMessagingReducer(startingState, dismissAction)

        assertTrue(resultState.shownMessageQueueIds.isEmpty())

        val modalState = resultState.modalMessageState as ModalMessageState.Dismissed
        assertEquals(testMessage, modalState.message)
    }

    @Test
    fun messageLoadingFailed_givenInlineMessage_expectInlineMessageToBeDismissed() {
        val elementId = String.random
        val testMessage = createTestMessage(persistent = false, elementId = elementId)

        val startingState = initialState.copy(
            queuedInlineMessagesState = QueuedInlineMessagesState()
                .addMessage(message = testMessage, elementId = elementId),
            shownMessageQueueIds = emptySet()
        )

        val dismissAction = InAppMessagingAction.EngineAction.MessageLoadingFailed(message = testMessage)
        val resultState = inAppMessagingReducer(startingState, dismissAction)

        assertTrue(resultState.shownMessageQueueIds.isEmpty())

        val inlineMessageStateMap = resultState.queuedInlineMessagesState.messagesByElementId
        assertEquals(1, inlineMessageStateMap.count())
        val inlineMessageState = inlineMessageStateMap[elementId] as InlineMessageState.Dismissed
        assertEquals(testMessage, inlineMessageState.message)
    }

    @Test
    fun reset_givenMessageInState_expectAllStateCleared() {
        val testMessage = createTestMessage(persistent = true)
        val startingState = initialState.copy(
            userId = "testuser123",
            anonymousId = "anon456",
            messagesInQueue = setOf(testMessage),
            shownMessageQueueIds = setOf(testMessage.queueId!!),
            modalMessageState = ModalMessageState.Displayed(testMessage)
        )

        val resetAction = InAppMessagingAction.Reset
        val resultState = inAppMessagingReducer(startingState, resetAction)

        assertTrue(resultState.messagesInQueue.isEmpty())
        assertTrue(resultState.shownMessageQueueIds.isEmpty())
        assertTrue(resultState.modalMessageState is ModalMessageState.Initial)
        assertNull(resultState.userId)
        assertNull(resultState.anonymousId)
        assertNull(resultState.currentRoute)
    }

    @Test
    fun updateInboxMessageOpenedStatus_givenMatchingQueueId_expectMessageUpdated() {
        val queueId = "queue-123"
        val message1 = InboxMessage(deliveryId = "inbox1", queueId = queueId, opened = false)
        val message2 = InboxMessage(deliveryId = "inbox2", queueId = "queue-456", opened = false)

        val startingState = initialState.copy(
            inboxMessages = setOf(message1, message2)
        )

        val action = InAppMessagingAction.InboxAction.UpdateOpened(
            message = message1,
            opened = true
        )
        val resultState = inAppMessagingReducer(startingState, action)

        assertEquals(2, resultState.inboxMessages.size)
        val updatedMessage = resultState.inboxMessages.first { it.queueId == queueId }
        assertTrue(updatedMessage.opened)
        val unchangedMessage = resultState.inboxMessages.first { it.queueId == "queue-456" }
        assertFalse(unchangedMessage.opened)
    }

    @Test
    fun updateInboxMessageOpenedStatus_givenNullQueueId_expectStateUnchanged() {
        val message1 = InboxMessage(deliveryId = "inbox1", queueId = null, opened = false)
        val message2 = InboxMessage(deliveryId = "inbox2", queueId = "queue-456", opened = false)

        val startingState = initialState.copy(
            inboxMessages = setOf(message1, message2)
        )

        val action = InAppMessagingAction.InboxAction.UpdateOpened(
            message = message1,
            opened = true
        )
        val resultState = inAppMessagingReducer(startingState, action)

        // State should remain unchanged when queueId is null
        assertEquals(startingState.inboxMessages, resultState.inboxMessages)
        resultState.inboxMessages.forEach { message ->
            assertFalse(message.opened)
        }
    }

    @Test
    fun updateInboxMessageOpenedStatus_givenMarkAsUnopened_expectOpenedSetToFalse() {
        val queueId = "queue-123"
        val message1 = InboxMessage(deliveryId = "inbox1", queueId = queueId, opened = true)
        val message2 = InboxMessage(deliveryId = "inbox2", queueId = "queue-456", opened = true)

        val startingState = initialState.copy(
            inboxMessages = setOf(message1, message2)
        )

        val action = InAppMessagingAction.InboxAction.UpdateOpened(
            message = message1,
            opened = false
        )
        val resultState = inAppMessagingReducer(startingState, action)

        assertEquals(2, resultState.inboxMessages.size)
        val updatedMessage = resultState.inboxMessages.first { it.queueId == queueId }
        assertFalse(updatedMessage.opened)
        val unchangedMessage = resultState.inboxMessages.first { it.queueId == "queue-456" }
        assertTrue(unchangedMessage.opened)
    }

    /**
     * Helper method to create a test message with customizable persistence
     */
    private fun createTestMessage(
        persistent: Boolean,
        queueId: String? = String.random,
        elementId: String? = null
    ): Message {
        val messageId = String.random

        return mockk<Message>(relaxed = true) {
            every { this@mockk.messageId } returns messageId
            every { this@mockk.queueId } returns queueId
            every { this@mockk.gistProperties } returns GistProperties(
                routeRule = null,
                elementId = elementId,
                campaignId = null,
                position = MessagePosition.CENTER,
                persistent = persistent,
                overlayColor = null,
                broadcast = null
            )
            every { this@mockk.isEmbedded } returns (elementId != null)
        }
    }
}
