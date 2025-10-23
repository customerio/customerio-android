package io.customer.messaginginapp.gist.data.sse

import androidx.annotation.VisibleForTesting
import io.customer.sdk.core.util.Logger

/**
 * Empty SSE connection manager for Phase 0 implementation.
 * This class only logs when SSE connection should be started.
 * Actual SSE implementation will be added in future phases.
 */
internal class SseConnectionManager(
    private val logger: Logger
) {

    /**
     * Start SSE connection (Phase 0: only logs)
     */
    internal fun startConnection() {
        logger.info("SSE connection should be started at this point")
        logger.debug("Phase 0: Empty SSE implementation - actual connection will be implemented in future phases")
    }

    /**
     * Stop SSE connection (Phase 0: only logs)
     */
    internal fun stopConnection() {
        logger.info("SSE connection should be stopped at this point")
        logger.debug("Phase 0: Empty SSE implementation - actual disconnection will be implemented in future phases")
    }

    /**
     * Check if SSE connection is active (Phase 0: always false)
     * This method is only visible for testing purposes.
     */
    @VisibleForTesting
    internal fun isConnected(): Boolean {
        logger.debug("Phase 0: SSE connection is not actually implemented yet")
        return false
    }
}
