package io.customer.messaginginapp.state

import io.customer.commontest.extensions.random
import io.customer.messaginginapp.state.MessageBuilderMock.createMessage
import io.customer.messaginginapp.testutils.core.JUnitTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for state transitions specific to inline in-app messaging.
 * These tests focus on key state transitions and edge cases not covered
 * in other test suites.
 */
class InlineMessageStateTest : JUnitTest() {

    @Test
    fun embedMessages_givenMultipleMessages_expectAllAddedToState() {
        val initialState = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random,
            userId = String.random
        )

        val element1 = String.random
        val element2 = String.random
        val message1 = createMessage(elementId = element1)
        val message2 = createMessage(elementId = element2)

        val embedAction = InAppMessagingAction.EmbedMessages(listOf(message1, message2))
        val resultState = inAppMessagingReducer(initialState, embedAction)

        val inlineStates = resultState.queuedInlineMessagesState.messagesByElementId
        assertEquals(2, inlineStates.size)

        val state1 = inlineStates[element1] as? InlineMessageState.ReadyToEmbed
        val state2 = inlineStates[element2] as? InlineMessageState.ReadyToEmbed

        assertEquals(message1, state1?.message)
        assertEquals(message2, state2?.message)
    }

    @Test
    fun embedMessages_givenMessageWithSameElementId_expectElementStateUpdated() {
        val elementId = String.random
        val existingMessage = createMessage(elementId = elementId)

        val initialState = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random,
            userId = String.random,
            queuedInlineMessagesState = QueuedInlineMessagesState()
                .addMessage(existingMessage, elementId)
        )

        val newMessage = createMessage(elementId = elementId)
        val embedAction = InAppMessagingAction.EmbedMessages(listOf(newMessage))
        val resultState = inAppMessagingReducer(initialState, embedAction)

        val inlineState = resultState.queuedInlineMessagesState.getMessage(elementId) as? InlineMessageState.ReadyToEmbed
        assertEquals(newMessage, inlineState?.message)
    }

    @Test
    fun displayMessage_givenInlineMessage_expectStateUpdatedToEmbedded() {
        val elementId = String.random
        val message = createMessage(elementId = elementId)

        val initialState = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random,
            userId = String.random,
            queuedInlineMessagesState = QueuedInlineMessagesState()
                .addMessage(message, elementId)
        )

        val displayAction = InAppMessagingAction.DisplayMessage(message)
        val resultState = inAppMessagingReducer(initialState, displayAction)

        val messageState = resultState.queuedInlineMessagesState.getMessage(elementId)
        assertTrue(messageState is InlineMessageState.Embedded)
        assertEquals(message, (messageState as InlineMessageState.Embedded).message)
    }

    @Test
    fun dismissMessage_givenInlineMessage_expectStateUpdatedToDismissed() {
        val elementId = String.random
        val message = createMessage(elementId = elementId)

        val queuedState = QueuedInlineMessagesState()
            .addMessage(message, elementId)
            .updateMessageState(message.queueId!!, InlineMessageState.Embedded(message, elementId))

        val initialState = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random,
            userId = String.random,
            queuedInlineMessagesState = queuedState
        )

        val dismissAction = InAppMessagingAction.DismissMessage(message)
        val resultState = inAppMessagingReducer(initialState, dismissAction)

        val messageState = resultState.queuedInlineMessagesState.getMessage(elementId)
        assertTrue(messageState is InlineMessageState.Dismissed)
        assertEquals(message, (messageState as InlineMessageState.Dismissed).message)
    }

    @Test
    fun messageLoadingFailed_givenInlineMessage_expectStateUpdatedToDismissed() {
        val elementId = String.random
        val message = createMessage(elementId = elementId)

        val queuedState = QueuedInlineMessagesState()
            .addMessage(message, elementId)
            .updateMessageState(message.queueId!!, InlineMessageState.Embedded(message, elementId))

        val initialState = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random,
            userId = String.random,
            queuedInlineMessagesState = queuedState
        )

        val loadingFailedAction = InAppMessagingAction.EngineAction.MessageLoadingFailed(message)
        val resultState = inAppMessagingReducer(initialState, loadingFailedAction)

        val messageState = resultState.queuedInlineMessagesState.getMessage(elementId)
        assertTrue(messageState is InlineMessageState.Dismissed)
        assertEquals(message, (messageState as InlineMessageState.Dismissed).message)
    }

    @Test
    fun reset_givenInlineMessages_expectAllMessagesCleared() {
        val element1 = String.random
        val element2 = String.random
        val message1 = createMessage(elementId = element1)
        val message2 = createMessage(elementId = element2)

        val queuedState = QueuedInlineMessagesState()
            .addMessage(message1, element1)
            .addMessage(message2, element2)

        val initialState = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random,
            userId = String.random,
            queuedInlineMessagesState = queuedState
        )

        val resetAction = InAppMessagingAction.Reset
        val resultState = inAppMessagingReducer(initialState, resetAction)

        assertTrue(resultState.queuedInlineMessagesState.messagesByElementId.isEmpty())
    }

    @Test
    fun updateMessageState_givenQueueIdDoesNotExist_expectStateUnchanged() {
        val elementId = String.random
        val message = createMessage(elementId = elementId)
        val nonExistentQueueId = String.random

        val initialState = QueuedInlineMessagesState()
            .addMessage(message, elementId)

        val resultState = initialState.updateMessageState(
            nonExistentQueueId,
            InlineMessageState.Dismissed(message)
        )

        assertEquals(initialState, resultState)
    }

    @Test
    fun queuedInlineMessagesState_givenMultipleMessages_expectCorrectList() {
        val element1 = String.random
        val element2 = String.random
        val message1 = createMessage(elementId = element1)
        val message2 = createMessage(elementId = element2)

        val state = QueuedInlineMessagesState()
            .addMessage(message1, element1)
            .addMessage(message2, element2)

        val allMessages = state.allMessages()

        assertEquals(2, allMessages.size)
        assertTrue(allMessages.any { it is InlineMessageState.ReadyToEmbed && it.message == message1 })
        assertTrue(allMessages.any { it is InlineMessageState.ReadyToEmbed && it.message == message2 })
    }

    @Test
    fun getMessage_givenElementIdDoesNotExist_expectNull() {
        val state = QueuedInlineMessagesState()

        val result = state.getMessage(String.random)

        assertEquals(null, result)
    }

    @Test
    fun withMessageDismissed_givenInlineMessageWithoutQueueId_expectStateUnchanged() {
        val elementId = String.random
        val message = createMessage(queueId = null, elementId = elementId)

        val initialState = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random,
            userId = String.random
        )

        val resultState = initialState.withMessageDismissed(message, shouldMarkAsShown = true)

        assertEquals(initialState, resultState)
    }

    @Test
    fun stateTransitionSequence_givenInlineMessage_expectCorrectStateTransitions() {
        var state = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random,
            userId = String.random
        )

        val elementId = String.random
        val message = createMessage(elementId = elementId)

        state = inAppMessagingReducer(state, InAppMessagingAction.EmbedMessages(listOf(message)))
        var messageState = state.queuedInlineMessagesState.getMessage(elementId)
        assertTrue(messageState is InlineMessageState.ReadyToEmbed)

        state = inAppMessagingReducer(state, InAppMessagingAction.DisplayMessage(message))
        messageState = state.queuedInlineMessagesState.getMessage(elementId)
        assertTrue(messageState is InlineMessageState.Embedded)

        state = inAppMessagingReducer(state, InAppMessagingAction.DismissMessage(message))
        messageState = state.queuedInlineMessagesState.getMessage(elementId)
        assertTrue(messageState is InlineMessageState.Dismissed)

        state = inAppMessagingReducer(state, InAppMessagingAction.Reset)
        assertTrue(state.queuedInlineMessagesState.messagesByElementId.isEmpty())
    }
}
