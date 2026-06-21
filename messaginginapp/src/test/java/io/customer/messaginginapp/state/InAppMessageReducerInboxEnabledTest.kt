package io.customer.messaginginapp.state

import io.customer.messaginginapp.testutils.core.JUnitTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InAppMessageReducerInboxEnabledTest : JUnitTest() {

    @Test
    fun testSetInboxEnabled_givenFalseState_thenSetsInboxEnabledToTrue() {
        val initialState = InAppMessagingState(isInboxEnabled = false)
        val action = InAppMessagingAction.SetInboxEnabled(true)

        val newState = inAppMessagingReducer(initialState, action)

        assertTrue(newState.isInboxEnabled)
    }

    @Test
    fun testSetInboxEnabled_givenTrueState_thenSetsInboxEnabledToFalse() {
        val initialState = InAppMessagingState(isInboxEnabled = true)
        val action = InAppMessagingAction.SetInboxEnabled(false)

        val newState = inAppMessagingReducer(initialState, action)

        assertFalse(newState.isInboxEnabled)
    }

    @Test
    fun testSetInboxEnabled_givenStateWithOtherProperties_thenPreservesOtherStateProperties() {
        val initialState = InAppMessagingState(
            siteId = "test-site",
            userId = "test-user",
            sseEnabled = true,
            isInboxEnabled = false
        )
        val action = InAppMessagingAction.SetInboxEnabled(true)

        val newState = inAppMessagingReducer(initialState, action)

        assertEquals("test-site", newState.siteId)
        assertEquals("test-user", newState.userId)
        assertTrue(newState.sseEnabled)
        assertTrue(newState.isInboxEnabled)
    }

    @Test
    fun testReset_givenInboxEnabledTrue_thenResetsInboxEnabledToFalse() {
        val initialState = InAppMessagingState(isInboxEnabled = true)
        val action = InAppMessagingAction.Reset

        val newState = inAppMessagingReducer(initialState, action)

        assertFalse(newState.isInboxEnabled)
    }
}
