package io.customer.messaginginapp.gist.data.sse

import io.customer.sdk.core.util.Logger

/**
 * Logger for SSE (Server-Sent Events) operations in the in-app messaging module.
 *
 * This logger centralizes all SSE-related logging to ensure consistent log levels
 * and message formats suitable for production. Almost all logs are debug level;
 * only critical errors (flow collector failures, emit failures) use error level.
 */
internal class InAppSseLogger(private val logger: Logger) {

    companion object {
        const val TAG = "SSE"
    }

    // =====================
    // Connection Management
    // =====================

    fun logStartingConnection() {
        logger.debug(tag = TAG, message = "Starting connection")
    }

    fun logConnectionAlreadyActive(state: SseConnectionState) {
        logger.debug(tag = TAG, message = "Connection already active (state: $state)")
    }

    fun logConnectionStopping() {
        logger.debug(tag = TAG, message = "Stopping connection")
    }

    fun logConnectionStopped() {
        logger.debug(tag = TAG, message = "Connection stopped")
    }

    fun logConnectionCancelled() {
        logger.debug(tag = TAG, message = "Connection cancelled")
    }

    fun logEstablishingConnection(userToken: String, sessionId: String) {
        logger.debug(tag = TAG, message = "Establishing connection for user: $userToken, session: $sessionId")
    }

    fun logConnectionEstablished() {
        logger.debug(tag = TAG, message = "Connection established successfully")
    }

    fun logConnectionOpened() {
        logger.debug(tag = TAG, message = "Connection opened")
    }

    fun logConnectionConfirmed() {
        logger.debug(tag = TAG, message = "Connection confirmed")
    }

    fun logConnectionClosed() {
        logger.debug(tag = TAG, message = "Connection closed")
    }

    fun logConnectionFailed(errorMessage: String?, responseCode: Int?) {
        logger.debug(tag = TAG, message = "Connection failed - ${errorMessage ?: "unknown error"}, code: $responseCode")
    }

    fun logNoUserTokenAvailable() {
        logger.error(tag = TAG, message = "Cannot establish connection: no user token available")
    }

    // =====================
    // SSE Events
    // =====================

    fun logReceivedEvent(type: String?) {
        logger.debug(tag = TAG, message = "Received event - type: $type")
    }

    fun logReceivedEventWithNoTypeOrData() {
        logger.debug(tag = TAG, message = "Received event with no type or data")
    }

    fun logReceivedHeartbeat() {
        logger.debug(tag = TAG, message = "Received heartbeat")
    }

    fun logReceivedMessages(count: Int, type: String?) {
        logger.debug(tag = TAG, message = "Received $count $type messages")
    }

    fun logReceivedEmptyMessagesEvent() {
        logger.debug(tag = TAG, message = "Received empty messages event")
    }

    fun logFailedToParseMessages(errorMessage: String?) {
        logger.debug(tag = TAG, message = "Failed to parse messages: $errorMessage")
    }

    fun logTtlExceeded() {
        logger.debug(tag = TAG, message = "TTL exceeded - reconnecting")
    }

    fun logUnknownEventType(eventType: String) {
        logger.debug(tag = TAG, message = "Unknown event type: $eventType")
    }

    // =====================
    // Channel Events (trySend failures)
    // =====================

    fun logFailedToSendConnectionOpenedEvent(errorMessage: String?) {
        logger.debug(tag = TAG, message = "Failed to send connection opened event: $errorMessage")
    }

    fun logFailedToSendEvent(errorMessage: String?) {
        logger.debug(tag = TAG, message = "Failed to send event: $errorMessage")
    }

    fun logFailedToSendErrorEvent(errorMessage: String?) {
        logger.debug(tag = TAG, message = "Failed to send error event: $errorMessage")
    }

    fun logFailedToSendConnectionClosedEvent() {
        logger.debug(tag = TAG, message = "Failed to send connection closed event")
    }

    fun logFlowCancelled() {
        logger.debug(tag = TAG, message = "Flow cancelled, cleaning up")
    }

    fun logDisconnectingService() {
        logger.debug(tag = TAG, message = "Disconnecting service")
    }

    fun logCreatingRequest(url: String) {
        logger.debug(tag = TAG, message = "Creating request to: $url")
    }

    // =====================
    // Retry Logic
    // =====================

    fun logConnectionAttemptFailed(errorClass: String?, errorMessage: String?) {
        logger.debug(tag = TAG, message = "Connection attempt failed - $errorClass: $errorMessage")
    }

    fun logConnectionFailure(errorClass: String?, shouldRetry: Boolean) {
        logger.debug(tag = TAG, message = "Connection failed - $errorClass, retryable: $shouldRetry")
    }

    fun logRetryingConnection(attemptCount: Int, maxRetries: Int) {
        logger.debug(tag = TAG, message = "Retrying connection (attempt $attemptCount/$maxRetries)")
    }

    fun logMaxRetriesExceeded(retryCount: Int, maxRetryCount: Int) {
        logger.debug(tag = TAG, message = "Max retries exceeded ($retryCount/$maxRetryCount) - falling back to polling")
    }

    fun logNonRetryableError(error: SseError) {
        logger.debug(tag = TAG, message = "Non-retryable error - falling back to polling ($error)")
    }

    fun logFallingBackToPolling() {
        logger.debug(tag = TAG, message = "Max retries reached - falling back to polling")
    }

    fun logRetryEmitFailed() {
        logger.error(tag = TAG, message = "Retry emit failed")
    }

    // =====================
    // Heartbeat Timer
    // =====================

    fun logHeartbeatTimerStarting(timeoutMs: Long) {
        logger.debug(tag = TAG, message = "Heartbeat timer starting with ${timeoutMs}ms timeout")
    }

    fun logHeartbeatTimerExpired(timeoutMs: Long) {
        logger.debug(tag = TAG, message = "Heartbeat timer expired after ${timeoutMs}ms")
    }

    fun logHeartbeatTimerCancelled() {
        logger.debug(tag = TAG, message = "Heartbeat timer cancelled")
    }

    fun logHeartbeatTimerResetting() {
        logger.debug(tag = TAG, message = "Heartbeat timer resetting")
    }

    fun logHeartbeatTimerExpiredTriggeringRetry() {
        logger.debug(tag = TAG, message = "Heartbeat timer expired - triggering retry logic")
    }

    // =====================
    // Data Parsing
    // =====================

    fun logReceivedEmptyMessageData() {
        logger.debug(tag = TAG, message = "Received empty or blank message data")
    }

    fun logMessageParsingFailedInvalidJson(errorMessage: String?, data: String) {
        logger.debug(tag = TAG, message = "Failed to parse messages - invalid JSON: $errorMessage, data: $data")
    }

    fun logMessageParsingError(errorMessage: String?, data: String) {
        logger.debug(tag = TAG, message = "Error parsing messages: $errorMessage, data: $data")
    }

    fun logHeartbeatTimeoutNoData() {
        logger.debug(tag = TAG, message = "Heartbeat event has no data, using default timeout")
    }

    fun logHeartbeatTimeoutParsingFailed(errorMessage: String?, data: String) {
        logger.debug(tag = TAG, message = "Failed to parse heartbeat timeout - invalid JSON: $errorMessage, data: $data")
    }

    fun logHeartbeatTimeoutParsingError(errorMessage: String?, data: String) {
        logger.debug(tag = TAG, message = "Error parsing heartbeat timeout: $errorMessage, data: $data")
    }

    fun logFilteredInvalidInboxMessages(count: Int) {
        logger.debug(tag = TAG, message = "Filtered out $count invalid inbox message(s) from SSE")
    }

    // =====================
    // Flow Collector Errors
    // =====================

    fun logTimeoutFlowCollectorError(errorClass: String?, errorMessage: String?, throwable: Throwable) {
        logger.error(
            tag = TAG,
            message = "Timeout flow collector error: $errorClass - $errorMessage",
            throwable = throwable
        )
    }

    fun logRetryCollectorError(errorClass: String?, errorMessage: String?, throwable: Throwable) {
        logger.error(
            tag = TAG,
            message = "Retry collector error: $errorClass - $errorMessage",
            throwable = throwable
        )
    }

    // =====================
    // Lifecycle Management
    // =====================

    fun logAppForegrounded() {
        logger.debug(tag = TAG, message = "App foregrounded with SSE enabled and user identified, starting connection")
    }

    fun logAppForegroundedSseNotUsed(sseEnabled: Boolean, isUserIdentified: Boolean) {
        logger.debug(tag = TAG, message = "App foregrounded but SSE not used (sseEnabled=$sseEnabled, isUserIdentified=$isUserIdentified) - using polling")
    }

    fun logAppBackgrounded() {
        logger.debug(tag = TAG, message = "App backgrounded, stopping connection")
    }

    fun logSseFlagChanged(sseEnabled: Boolean) {
        logger.debug(tag = TAG, message = "SSE flag changed to: $sseEnabled")
    }

    fun logSseFlagChangedWhileBackgrounded(sseEnabled: Boolean) {
        logger.debug(tag = TAG, message = "SSE flag changed to $sseEnabled while backgrounded - will apply when foregrounded")
    }

    fun logSseEnabledWhileForegrounded() {
        logger.debug(tag = TAG, message = "SSE enabled while foregrounded and user identified, starting connection")
    }

    fun logSseEnabledButUserAnonymous() {
        logger.debug(tag = TAG, message = "SSE flag enabled but user is anonymous - using polling instead")
    }

    fun logSseDisabledWhileForegrounded() {
        logger.debug(tag = TAG, message = "SSE disabled while foregrounded, stopping connection")
    }

    fun logRestartingAfterReset() {
        logger.debug(tag = TAG, message = "App still foregrounded after reset, restarting connection")
    }

    fun logUserIdentificationChanged(isIdentified: Boolean) {
        logger.debug(tag = TAG, message = "User identification changed - isIdentified: $isIdentified")
    }

    fun logEnablingSseForIdentifiedUser() {
        logger.debug(tag = TAG, message = "User became identified, enabling SSE connection")
    }

    fun logDisablingSseForAnonymousUser() {
        logger.debug(tag = TAG, message = "User became anonymous, disabling SSE and falling back to polling")
    }

    // =====================
    // SSE Flag Detection (Queue)
    // =====================

    fun logSseFlagChangedFromTo(oldValue: Boolean, newValue: Boolean) {
        logger.debug(tag = TAG, message = "SSE flag changed from $oldValue to $newValue")
    }

    // =====================
    // Middleware
    // =====================

    fun logTryDisplayNextMessageAfterDismissal() {
        logger.debug(tag = TAG, message = "Try display next message after dismissal")
    }
}
