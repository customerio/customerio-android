package io.customer.messaginginapp.state

import io.customer.commontest.extensions.random
import io.customer.messaginginapp.state.MessageBuilderTest.createMessage
import io.customer.messaginginapp.testutils.core.JUnitTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests specifically for the QueuedInlineMessagesState class.
 */
class QueuedInlineMessagesStateTest : JUnitTest() {

    @Test
    fun addMessage_shouldCorrectlyAddMessageToState() {
        val state = QueuedInlineMessagesState()
        val elementId = String.random
        val message = createMessage(elementId = elementId)

        val newState = state.addMessage(message, elementId)

        val messageState = newState.getMessage(elementId)
        assertNotNull(messageState)
        assertEquals(InlineMessageState.ReadyToEmbed::class.java, messageState!!::class.java)
        assertEquals(message, (messageState as InlineMessageState.ReadyToEmbed).message)
        assertEquals(elementId, messageState.elementId)
    }

    @Test
    fun addMessage_whenExistingElementId_shouldReplaceMessage() {
        val elementId = String.random
        val existingMessage = createMessage(elementId = elementId)
        val initialState = QueuedInlineMessagesState().addMessage(existingMessage, elementId)

        val newMessage = createMessage(elementId = elementId)
        val updatedState = initialState.addMessage(newMessage, elementId)

        val messageState = updatedState.getMessage(elementId) as? InlineMessageState.ReadyToEmbed
        assertEquals(newMessage, messageState?.message)
    }

    @Test
    fun updateMessageState_shouldUpdateExistingMessageState() {
        val elementId = String.random
        val message = createMessage(elementId = elementId)
        val initialState = QueuedInlineMessagesState().addMessage(message, elementId)

        val newState = initialState.updateMessageState(
            message.queueId!!,
            InlineMessageState.Embedded(message, elementId)
        )

        val messageState = newState.getMessage(elementId)
        assertTrue(messageState is InlineMessageState.Embedded)
        assertEquals(message, (messageState as InlineMessageState.Embedded).message)
    }

    @Test
    fun updateMessageState_whenQueueIdNotFound_shouldReturnUnchangedState() {
        val elementId = String.random
        val message = createMessage(elementId = elementId)
        val initialState = QueuedInlineMessagesState().addMessage(message, elementId)

        val nonExistentQueueId = String.random
        val newState = initialState.updateMessageState(
            nonExistentQueueId,
            InlineMessageState.Embedded(message, elementId)
        )

        assertEquals(initialState, newState)
    }

    @Test
    fun getMessage_whenElementIdExists_shouldReturnCorrectState() {
        val elementId = String.random
        val message = createMessage(elementId = elementId)
        val state = QueuedInlineMessagesState().addMessage(message, elementId)

        val result = state.getMessage(elementId)

        assertNotNull(result)
        assertTrue(result is InlineMessageState.ReadyToEmbed)
        assertEquals(message, (result as InlineMessageState.ReadyToEmbed).message)
    }

    @Test
    fun getMessage_whenElementIdDoesNotExist_shouldReturnNull() {
        val state = QueuedInlineMessagesState()

        val result = state.getMessage(String.random)

        assertNull(result)
    }

    @Test
    fun allMessages_shouldReturnAllMessageStates() {
        val element1 = String.random
        val element2 = String.random
        val message1 = createMessage(elementId = element1)
        val message2 = createMessage(elementId = element2)

        val state = QueuedInlineMessagesState()
            .addMessage(message1, element1)
            .addMessage(message2, element2)

        val result = state.allMessages()

        assertEquals(2, result.size)
        assertTrue(result.any { it is InlineMessageState.ReadyToEmbed && it.message == message1 })
        assertTrue(result.any { it is InlineMessageState.ReadyToEmbed && it.message == message2 })
    }

    @Test
    fun allMessages_whenEmpty_shouldReturnEmptyList() {
        val state = QueuedInlineMessagesState()

        val result = state.allMessages()

        assertTrue(result.isEmpty())
    }

    @Test
    fun toString_shouldReturnFormattedString() {
        val elementId = String.random
        val message = createMessage(elementId = elementId)
        val state = QueuedInlineMessagesState().addMessage(message, elementId)

        val result = state.toString()

        assertTrue(result.contains("EmbeddedMessagesState"))
        assertTrue(result.contains("messages=1"))
        assertTrue(result.contains(elementId))
    }

    @Test
    fun equality_withIdenticalStates_shouldBeEqual() {
        val elementId = String.random
        val message = createMessage(elementId = elementId)
        val state1 = QueuedInlineMessagesState().addMessage(message, elementId)
        val state2 = QueuedInlineMessagesState().addMessage(message, elementId)

        assertEquals(state1, state2)
        assertEquals(state1.hashCode(), state2.hashCode())
    }

    @Test
    fun equality_withDifferentStates_shouldNotBeEqual() {
        val element1 = String.random
        val element2 = String.random
        val message1 = createMessage(elementId = element1)
        val message2 = createMessage(elementId = element2)

        val state1 = QueuedInlineMessagesState().addMessage(message1, element1)
        val state2 = QueuedInlineMessagesState().addMessage(message2, element2)

        assertTrue(state1 != state2)
    }

    @Test
    fun stateTransitions_addDismissAdd_shouldHandleCorrectly() {
        val elementId = String.random
        val message1 = createMessage(elementId = elementId)

        var state = QueuedInlineMessagesState().addMessage(message1, elementId)

        state = state.updateMessageState(
            message1.queueId!!,
            InlineMessageState.Embedded(message1, elementId)
        )

        state = state.updateMessageState(
            message1.queueId!!,
            InlineMessageState.Dismissed(message1)
        )

        val message2 = createMessage(elementId = elementId)
        state = state.addMessage(message2, elementId)

        val messageState = state.getMessage(elementId)
        assertTrue(messageState is InlineMessageState.ReadyToEmbed)
        assertEquals(message2, (messageState as InlineMessageState.ReadyToEmbed).message)
    }
}
