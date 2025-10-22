package io.customer.messaginginapp.gist.data.sse

import io.customer.messaginginapp.testutils.core.JUnitTest
import io.customer.sdk.core.util.Logger
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SseConnectionManagerTest : JUnitTest() {

    private val mockLogger = mockk<Logger>(relaxed = true)
    private val sseConnectionManager = SseConnectionManager(mockLogger)

    @Test
    fun `test startConnection logs expected messages`() {
        sseConnectionManager.startConnection()

        verify { mockLogger.info("SSE connection should be started at this point") }
        verify { mockLogger.debug("Phase 0: Empty SSE implementation - actual connection will be implemented in future phases") }
    }

    @Test
    fun `test stopConnection logs expected messages`() {
        sseConnectionManager.stopConnection()

        verify { mockLogger.info("SSE connection should be stopped at this point") }
        verify { mockLogger.debug("Phase 0: Empty SSE implementation - actual disconnection will be implemented in future phases") }
    }

    @Test
    fun `test isConnected always returns false in Phase 0`() {
        val isConnected = sseConnectionManager.isConnected()

        assertFalse(isConnected)
        verify { mockLogger.debug("Phase 0: SSE connection is not actually implemented yet") }
    }
}
