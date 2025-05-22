package io.customer.messaginginapp.state

import io.customer.commontest.extensions.random
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.state.MessageBuilderMock.createMessage
import io.customer.messaginginapp.testutils.core.JUnitTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the InAppMessagingState class and its utility methods.
 */
class InAppMessagingStateTest : JUnitTest() {

    @Test
    fun constructor_withDefaultValues_shouldCreateValidState() {
        val state = InAppMessagingState()

        assertEquals("", state.siteId)
        assertEquals("", state.dataCenter)
        assertEquals(GistEnvironment.PROD, state.environment)
        assertEquals(600_000L, state.pollInterval)
        assertEquals(null, state.userId)
        assertEquals(null, state.currentRoute)
        assertTrue(state.modalMessageState is ModalMessageState.Initial)
        assertEquals(QueuedInlineMessagesState(), state.queuedInlineMessagesState)
        assertTrue(state.messagesInQueue.isEmpty())
        assertTrue(state.shownMessageQueueIds.isEmpty())
    }

    @Test
    fun toString_shouldIncludeAllFields() {
        val givenSiteId = String.random
        val givenUserId = String.random
        val state = InAppMessagingState(
            siteId = givenSiteId,
            userId = givenUserId
        )

        val result = state.toString()

        assertTrue(result.contains("siteId='$givenSiteId'"))
        assertTrue(result.contains("userId=$givenUserId"))
        assertTrue(result.contains("modalMessageState=Initial"))
        assertTrue(result.contains("embeddedMessagesState="))
        assertTrue(result.contains("messagesInQueue="))
        assertTrue(result.contains("shownMessageQueueIds="))
    }

    @Test
    fun diff_withIdenticalStates_shouldReturnEmptyMap() {
        val state1 = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random
        )
        val state2 = state1.copy()

        val result = state1.diff(state2)

        assertTrue(result.isEmpty())
    }

    @Test
    fun diff_withDifferentStates_shouldReturnMapOfDifferences() {
        val siteId1 = String.random
        val siteId2 = String.random
        val userId1 = String.random
        val userId2 = String.random

        val state1 = InAppMessagingState(
            siteId = siteId1,
            userId = userId1
        )
        val state2 = InAppMessagingState(
            siteId = siteId2,
            userId = userId2
        )

        val result = state1.diff(state2)

        assertEquals(2, result.size)
        assertEquals(Pair(siteId1, siteId2), result["siteId"])
        assertEquals(Pair(userId1, userId2), result["userId"])
    }

    @Test
    fun withUpdatedEmbeddedMessage_shouldUpdateInlineMessageState() {
        val elementId = String.random
        val message = createMessage(elementId = elementId)
        val initialState = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random,
            queuedInlineMessagesState = QueuedInlineMessagesState()
                .addMessage(message, elementId)
        )

        val newState = initialState.withUpdatedEmbeddedMessage(
            queueId = message.queueId!!,
            newState = InlineMessageState.Embedded(message, elementId)
        )

        val messageState = newState.queuedInlineMessagesState.getMessage(elementId)
        assertTrue(messageState is InlineMessageState.Embedded)
        assertEquals(message, (messageState as InlineMessageState.Embedded).message)
    }

    @Test
    fun withUpdatedEmbeddedMessage_withCustomShownMessageIds_shouldUpdateShownIds() {
        val elementId = String.random
        val message = createMessage(elementId = elementId)
        val initialState = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random,
            queuedInlineMessagesState = QueuedInlineMessagesState()
                .addMessage(message, elementId)
        )

        val customShownIds = setOf(String.random, String.random)
        val newState = initialState.withUpdatedEmbeddedMessage(
            queueId = message.queueId!!,
            newState = InlineMessageState.Embedded(message, elementId),
            shownMessageQueueIds = customShownIds
        )

        assertEquals(customShownIds, newState.shownMessageQueueIds)
    }

    @Test
    fun withMessageDismissed_forInlineMessage_shouldUpdateToInlineDismissedState() {
        val elementId = String.random
        val message = createMessage(elementId = elementId)

        val embeddedState = QueuedInlineMessagesState()
            .addMessage(message, elementId)
            .updateMessageState(message.queueId!!, InlineMessageState.Embedded(message, elementId))

        val initialState = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random,
            queuedInlineMessagesState = embeddedState
        )

        val newState = initialState.withMessageDismissed(message, shouldMarkAsShown = true)

        val messageState = newState.queuedInlineMessagesState.getMessage(elementId)
        assertTrue(messageState is InlineMessageState.Dismissed)
        assertEquals(message, (messageState as InlineMessageState.Dismissed).message)

        assertTrue(newState.shownMessageQueueIds.contains(message.queueId))
    }

    @Test
    fun withMessageDismissed_forModalMessage_shouldUpdateToModalDismissedState() {
        val message = createMessage()
        val initialState = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random,
            modalMessageState = ModalMessageState.Displayed(message)
        )

        val newState = initialState.withMessageDismissed(message, shouldMarkAsShown = true)

        assertTrue(newState.modalMessageState is ModalMessageState.Dismissed)
        assertEquals(message, (newState.modalMessageState as ModalMessageState.Dismissed).message)

        assertTrue(newState.shownMessageQueueIds.contains(message.queueId))
    }

    @Test
    fun equality_identicalStates_shouldBeEqual() {
        val state1 = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random,
            userId = String.random
        )
        val state2 = state1.copy()

        assertEquals(state1, state2)
        assertEquals(state1.hashCode(), state2.hashCode())
    }

    @Test
    fun equality_differentStates_shouldNotBeEqual() {
        val state1 = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random
        )
        val state2 = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random
        )

        assertNotEquals(state1, state2)
    }

    @Test
    fun copy_shouldCreateNewIdenticalState() {
        val originalState = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random,
            userId = String.random
        )

        val copiedState = originalState.copy()

        assertEquals(originalState, copiedState)
        assertTrue(originalState !== copiedState)
    }
}
