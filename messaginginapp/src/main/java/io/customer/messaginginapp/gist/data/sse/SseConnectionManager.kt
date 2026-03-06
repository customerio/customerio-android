package io.customer.messaginginapp.gist.data.sse

import io.customer.messaginginapp.gist.data.NetworkUtilities
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SSE connection manager with retry logic.
 *
 * Establishes and manages Server-Sent Events connections to the Customer.io SSE endpoint.
 * Handles connection lifecycle, event parsing, and message delivery to the in-app messaging queue.
 * Integrates with SseRetryHelper for automatic retry behavior on connection failures.
 *
 * Connection state transitions:
 * - DISCONNECTED -> CONNECTING (startConnection)
 * - CONNECTING -> CONNECTED (ConnectionOpenEvent/CONNECTED event from server)
 * - CONNECTED -> DISCONNECTING (stopConnection)
 * - CONNECTING/CONNECTED -> DISCONNECTED (ConnectionFailedEvent/ConnectionClosedEvent from SseService)
 * - DISCONNECTING -> DISCONNECTED (stopConnection completes, or ConnectionClosedEvent if disconnect() was called)
 *
 * Job lifecycle:
 *
 * **timeoutJob (Heartbeat Timeout Collector)**:
 * - Starts: When `subscribeToFlows()` is called (during `startConnection()`)
 * - Purpose: Collects heartbeat timeout events from `HeartbeatTimer` and triggers retry logic
 * - Cancelled: Only in `stopConnection()` when explicitly stopping the connection
 * - Persists: Across connection attempts and retries - remains active throughout the manager's lifetime
 *
 * **retryJob (Retry Decision Collector)**:
 * - Starts: When `subscribeToFlows()` is called (during `startConnection()`)
 * - Purpose: Collects retry decisions from `SseRetryHelper` and acts on them (start connection, fallback to polling)
 * - Cancelled: Only in `stopConnection()` when explicitly stopping the connection
 * - Persists: Across connection attempts and retries - remains active throughout the manager's lifetime
 *
 * **HeartbeatTimer**:
 * - Started: In `setupSuccessfulConnection()` when connection is confirmed (ConnectionOpenEvent/CONNECTED event)
 * - Reset: In `handleConnectionFailure()`, `cleanupForReconnect()`, `handleCompleteFailure()`, and `ConnectionClosedEvent` handler
 * - Purpose: Monitors server heartbeats and triggers timeout if no heartbeat received within the timeout period
 */
internal class SseConnectionManager(
    private val sseLogger: InAppSseLogger,
    private val sseService: SseService,
    private val sseDataParser: SseDataParser,
    private val inAppMessagingManager: InAppMessagingManager,
    private val heartbeatTimer: HeartbeatTimer,
    private val retryHelper: SseRetryHelper,
    private val scope: CoroutineScope
) {

    private val connectionMutex = Mutex()
    private var connectionJob: Job? = null
    private var timeoutJob: Job? = null
    private var retryJob: Job? = null
    private var connectionState = SseConnectionState.DISCONNECTED

    /**
     * Start SSE connection.
     *
     * This method is thread-safe. It prepares for a new connection attempt by cancelling old jobs,
     * setting state, ensuring collectors are running, and launching the connection job - all within
     * a mutex-protected block to prevent race conditions.
     *
     * Allows connection attempts from DISCONNECTING state - the old connection's event collection
     * will be cancelled, so we won't receive disconnected events from the old connection.
     */
    internal fun startConnection() {
        scope.launch {
            sseLogger.logStartingConnection()

            connectionMutex.withLock {
                val currentState = connectionState
                if (currentState == SseConnectionState.CONNECTING || currentState == SseConnectionState.CONNECTED) {
                    sseLogger.logConnectionAlreadyActive(currentState)
                    return@launch
                }

                connectionJob?.cancel()
                connectionState = SseConnectionState.CONNECTING

                // Ensure collectors are running (they may have been canceled by stopConnection())
                subscribeToFlows()

                // Assign connectionJob inside mutex to prevent race conditions
                connectionJob = scope.launch {
                    executeConnectionAttempt()
                }
            }
        }
    }

    /**
     * Stop SSE connection.
     *
     * This method is thread-safe and will gracefully close the connection.
     */
    internal fun stopConnection() {
        scope.launch {
            sseLogger.logConnectionStopping()

            connectionMutex.withLock {
                connectionState = SseConnectionState.DISCONNECTING

                connectionJob?.cancel()
                connectionJob = null

                timeoutJob?.cancel()
                timeoutJob = null

                retryHelper.resetRetryState()
                retryJob?.cancel()
                retryJob = null

                sseService.disconnect()
                connectionState = SseConnectionState.DISCONNECTED
            }

            sseLogger.logConnectionStopped()
        }
    }

    /**
     * Executes the actual connection attempt and handles failures.
     * This is the core connection logic that can fail and trigger retries.
     */
    private suspend fun executeConnectionAttempt() {
        try {
            establishConnection()
        } catch (e: CancellationException) {
            sseLogger.logConnectionCancelled()
            // Don't update state - the code that cancelled this job has already updated it.
            throw e
        } catch (e: Exception) {
            sseLogger.logConnectionAttemptFailed(e::class.simpleName, e.message)
            val sseError = classifySseError(e, null)
            handleConnectionFailure(sseError)
        }
    }

    private suspend fun establishConnection() {
        val currentState = inAppMessagingManager.getCurrentState()
        val userToken = currentState.userId ?: currentState.anonymousId

        if (userToken == null) {
            sseLogger.logNoUserTokenAvailable()
            // This is a configuration issue, not a network issue - use non-retryable ServerError
            val error = SseError.ServerError(
                throwable = IllegalStateException("Cannot establish connection: no user token available"),
                responseCode = 400,
                shouldRetry = false
            )
            handleConnectionFailure(error)
            return
        }

        // Prevent making connection if the coroutine has been cancelled
        currentCoroutineContext().ensureActive()
        sseLogger.logEstablishingConnection(userToken, currentState.sessionId)
        val eventFlow = sseService.connectSse(
            sessionId = currentState.sessionId,
            userToken = userToken,
            siteId = currentState.siteId
        )

        // Prevent state update and flow collection if the coroutine has been cancelled
        currentCoroutineContext().ensureActive()
        // State will be set to CONNECTED when we receive ConnectionOpenEvent or CONNECTED event
        sseLogger.logConnectionEstablished()

        eventFlow.collect { event ->
            handleSseEvent(event)
        }
    }

    private suspend fun handleSseEvent(event: SseEvent) {
        when (event) {
            is ConnectionOpenEvent -> {
                sseLogger.logConnectionOpened()
                setupSuccessfulConnection()
            }

            is ServerEvent -> {
                handleServerEvent(event)
            }

            is ConnectionFailedEvent -> {
                handleConnectionFailure(event.error)
            }

            ConnectionClosedEvent -> {
                sseLogger.logConnectionClosed()
                updateConnectionState(SseConnectionState.DISCONNECTED)
                heartbeatTimer.reset()
            }
        }
    }

    private suspend fun handleServerEvent(event: ServerEvent) {
        when (event.eventType) {
            ServerEvent.CONNECTED -> {
                sseLogger.logConnectionConfirmed()
                setupSuccessfulConnection()
            }

            ServerEvent.HEARTBEAT -> {
                sseLogger.logReceivedHeartbeat()
                val serverTimeout = sseDataParser.parseHeartbeatTimeout(event.data)
                heartbeatTimer.startTimer(serverTimeout + NetworkUtilities.HEARTBEAT_BUFFER_MS)
            }

            ServerEvent.MESSAGES -> {
                try {
                    val messages = sseDataParser.parseInAppMessages(event.data)
                    if (messages.isNotEmpty()) {
                        sseLogger.logReceivedMessages(messages.size, "in-app")
                        inAppMessagingManager.dispatch(
                            InAppMessagingAction.ProcessMessageQueue(
                                messages
                            )
                        )
                    } else {
                        sseLogger.logReceivedEmptyMessagesEvent()
                    }
                } catch (e: Exception) {
                    sseLogger.logFailedToParseMessages(e.message)
                }
            }

            ServerEvent.INBOX_MESSAGES -> {
                try {
                    val inboxMessages = sseDataParser.parseInboxMessages(event.data)
                    if (inboxMessages.isNotEmpty()) {
                        sseLogger.logReceivedMessages(inboxMessages.size, "inbox")
                        inAppMessagingManager.dispatch(
                            InAppMessagingAction.ProcessInboxMessages(inboxMessages)
                        )
                    } else {
                        sseLogger.logReceivedEmptyMessagesEvent()
                    }
                } catch (e: Exception) {
                    sseLogger.logFailedToParseMessages(e.message)
                }
            }

            ServerEvent.TTL_EXCEEDED -> {
                sseLogger.logTtlExceeded()
                cleanupForReconnect()
                startConnection()
            }

            else -> {
                sseLogger.logUnknownEventType(event.eventType)
            }
        }
    }

    /**
     * Handles connection failure: updates state, resets heartbeat, and schedules retry.
     * This is the common cleanup that happens between retry attempts.
     */
    private suspend fun handleConnectionFailure(error: SseError) {
        sseLogger.logConnectionFailure(error::class.simpleName, error.shouldRetry)

        // Cleanup between retries
        updateConnectionState(SseConnectionState.DISCONNECTED)
        heartbeatTimer.reset()

        // Schedule retry (or fallback if non-retryable)
        retryHelper.scheduleRetry(error)
    }

    /**
     * Handles complete failure: cleans up and falls back to polling.
     * This is called when max retries are reached or error is non-retryable.
     */
    private suspend fun handleCompleteFailure() {
        sseLogger.logFallingBackToPolling()

        // Cleanup on complete failure
        updateConnectionState(SseConnectionState.DISCONNECTED)
        heartbeatTimer.reset()
        retryHelper.resetRetryState()

        // Fallback to polling
        inAppMessagingManager.dispatch(InAppMessagingAction.SetSseEnabled(false))
    }

    /**
     * Cleans up state before reconnecting (e.g., after TTL_EXCEEDED).
     * Resets connection state, heartbeat timer, and retry state.
     */
    private suspend fun cleanupForReconnect() {
        updateConnectionState(SseConnectionState.DISCONNECTED)
        heartbeatTimer.reset()
        retryHelper.resetRetryState()
        // Don't cancel retryJob - it should persist to handle future retries
        // subscribeToFlows() will ensure it's running if needed
    }

    /**
     * Sets up state after successful connection: resets retry state and starts heartbeat timer.
     * This is called when connection is confirmed (ConnectionOpenEvent or CONNECTED event).
     */
    private suspend fun setupSuccessfulConnection() {
        // Set state to CONNECTED when we get connection open/connected event from server
        updateConnectionState(SseConnectionState.CONNECTED)
        retryHelper.resetRetryState()
        heartbeatTimer.startTimer(
            NetworkUtilities.DEFAULT_HEARTBEAT_TIMEOUT_MS + NetworkUtilities.HEARTBEAT_BUFFER_MS
        )
    }

    /**
     * Thread-safe method to update connection state.
     * This method handles mutex locking internally.
     */
    private suspend fun updateConnectionState(newState: SseConnectionState) {
        // Check if coroutine is cancelled before updating state
        // This prevents race conditions where a cancelled job tries to update state
        currentCoroutineContext().ensureActive()

        connectionMutex.withLock {
            connectionState = newState
        }
    }

    private fun subscribeToFlows() {
        if (timeoutJob == null) {
            timeoutJob = scope.launch {
                try {
                    heartbeatTimer.timeoutFlow.collect { event ->
                        if (event is HeartbeatTimeoutEvent) {
                            sseLogger.logHeartbeatTimerExpiredTriggeringRetry()
                            handleConnectionFailure(SseError.TimeoutError)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    sseLogger.logTimeoutFlowCollectorError(e::class.simpleName, e.message, e)
                    timeoutJob = null
                }
            }
        }

        if (retryJob == null) {
            retryJob = scope.launch {
                try {
                    retryHelper.retryDecisionFlow.collect { decision ->
                        // Filter out null values (initial state or after reset)
                        if (decision == null) {
                            return@collect
                        }

                        when (decision) {
                            is RetryDecision.RetryNow -> {
                                sseLogger.logRetryingConnection(decision.attemptCount, MAX_RETRY_COUNT)
                                startConnection()
                            }
                            is RetryDecision.MaxRetriesReached -> {
                                sseLogger.logFallingBackToPolling()
                                handleCompleteFailure()
                            }
                            is RetryDecision.RetryNotPossible -> {
                                sseLogger.logNonRetryableError(decision.error)
                                handleCompleteFailure()
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    sseLogger.logRetryCollectorError(e::class.simpleName, e.message, e)
                    retryJob = null
                }
            }
        }
    }

    companion object {
        private const val MAX_RETRY_COUNT = 3
    }
}
