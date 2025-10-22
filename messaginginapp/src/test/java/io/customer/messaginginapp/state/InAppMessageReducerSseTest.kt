package io.customer.messaginginapp.state

import io.customer.messaginginapp.testutils.core.JUnitTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InAppMessageReducerSseTest : JUnitTest() {

    @Test
    fun `test SetSseEnabled action sets sseEnabled to true`() {
        val initialState = InAppMessagingState(sseEnabled = false)
        val action = InAppMessagingAction.SetSseEnabled(true)

        val newState = inAppMessagingReducer(initialState, action)

        assertTrue(newState.sseEnabled)
    }

    @Test
    fun `test SetSseEnabled action sets sseEnabled to false`() {
        val initialState = InAppMessagingState(sseEnabled = true)
        val action = InAppMessagingAction.SetSseEnabled(false)

        val newState = inAppMessagingReducer(initialState, action)

        assertFalse(newState.sseEnabled)
    }

    @Test
    fun `test SetSseEnabled action preserves other state properties`() {
        val initialState = InAppMessagingState(
            siteId = "test-site",
            userId = "test-user",
            pollInterval = 300_000L,
            sseEnabled = false
        )
        val action = InAppMessagingAction.SetSseEnabled(true)

        val newState = inAppMessagingReducer(initialState, action)

        assertEquals("test-site", newState.siteId)
        assertEquals("test-user", newState.userId)
        assertEquals(300_000L, newState.pollInterval)
        assertTrue(newState.sseEnabled)
    }

    @Test
    fun `test Reset action resets sseEnabled to false`() {
        val initialState = InAppMessagingState(sseEnabled = true)
        val action = InAppMessagingAction.Reset

        val newState = inAppMessagingReducer(initialState, action)

        assertFalse(newState.sseEnabled)
    }
}
