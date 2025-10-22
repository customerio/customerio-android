package io.customer.messaginginapp.state

import io.customer.messaginginapp.testutils.core.JUnitTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InAppMessagingStateSseTest : JUnitTest() {

    @Test
    fun `test initial state has sseEnabled false`() {
        val state = InAppMessagingState()
        assertFalse(state.sseEnabled)
    }

    @Test
    fun `test sseEnabled can be set to true`() {
        val initialState = InAppMessagingState()
        val newState = initialState.copy(sseEnabled = true)
        assertTrue(newState.sseEnabled)
    }

    @Test
    fun `test sseEnabled can be set to false`() {
        val initialState = InAppMessagingState(sseEnabled = true)
        val newState = initialState.copy(sseEnabled = false)
        assertFalse(newState.sseEnabled)
    }

    @Test
    fun `test other state properties remain unchanged when updating sseEnabled`() {
        val initialState = InAppMessagingState(
            siteId = "test-site",
            userId = "test-user",
            pollInterval = 300_000L
        )
        val newState = initialState.copy(sseEnabled = true)

        assertEquals("test-site", newState.siteId)
        assertEquals("test-user", newState.userId)
        assertEquals(300_000L, newState.pollInterval)
        assertTrue(newState.sseEnabled)
    }
}
