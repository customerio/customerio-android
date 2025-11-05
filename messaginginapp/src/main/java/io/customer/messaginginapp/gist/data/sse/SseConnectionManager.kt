package io.customer.messaginginapp.gist.data.sse

import io.customer.messaginginapp.gist.data.NetworkUtilities
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.sdk.core.util.Logger
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SSE connection manager for Phase 2 implementation.
 *
 * Establishes and manages Server-Sent Events connections to the Customer.io SSE endpoint.
 * Handles connection lifecycle, event parsing, and message delivery to the in-app messaging queue.
 */
internal class SseConnectionManager(
    private val logger: Logger,
    private val sseService: SseService,
    private val sseDataParser: SseDataParser,
    private val inAppMessagingManager: InAppMessagingManager,
    private val heartbeatTimer: HeartbeatTimer,
    private val scope: CoroutineScope
) {

    private val connectionMutex = Mutex()
    private var connectionJob: Job? = null
    private var timeoutJob: Job? = null
    private var connectionState = SseConnectionState.DISCONNECTED

    /**
     * Start SSE connection (Phase 0: only logs)
     */
    internal fun startConnection() {
        scope.launch {
            logger.info("SSE: Starting connection")

            connectionMutex.withLock {
                val currentState = connectionState
                if (currentState == SseConnectionState.CONNECTING || currentState == SseConnectionState.CONNECTED) {
                    logger.debug("SSE: Connection already active (state: $currentState)")
                    return@withLock
                }

                connectionJob?.cancel()
                connectionJob = null
                timeoutJob?.cancel()
                timeoutJob = null
                connectionState = SseConnectionState.CONNECTING

                val job = scope.launch {
                    try {
                        establishConnection()
                    } catch (e: CancellationException) {
                        logger.debug("SSE: Connection cancelled")
                        updateConnectionState(SseConnectionState.DISCONNECTED)
                        throw e
                    } catch (e: Exception) {
                        logger.error("SSE: Connection failed: ${e.message}")
                        updateConnectionState(SseConnectionState.DISCONNECTED)
                    }
                }

                connectionJob = job
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
            logger.info("SSE: Stopping connection")

            connectionMutex.withLock {
                connectionJob?.cancel()
                connectionJob = null

                timeoutJob?.cancel()
                timeoutJob = null

                sseService.disconnect()

                connectionState = SseConnectionState.DISCONNECTED
            }

            logger.debug("SSE: Connection stopped")
        }
    }

    private suspend fun establishConnection() {
        val currentState = inAppMessagingManager.getCurrentState()
        val userToken = currentState.userId ?: currentState.anonymousId

        if (userToken == null) {
            logger.error("SSE: Cannot establish connection: no user token available")
            throw IllegalStateException("Cannot establish connection: no user token available")
        }

        // Prevent making connection if the coroutine has been cancelled
        coroutineContext.ensureActive()
        logger.debug("SSE: Establishing connection for user: $userToken, session: ${currentState.sessionId}")
        val eventFlow = sseService.connectSse(
            sessionId = currentState.sessionId,
            userToken = userToken,
            siteId = currentState.siteId
        )

        // Prevent state update and flow collection if the coroutine has been cancelled
        coroutineContext.ensureActive()
        updateConnectionState(SseConnectionState.CONNECTED)
        logger.info("SSE: Connection established successfully")

        // Start monitoring heartbeat timer timeout events
        timeoutJob = scope.launch {
            heartbeatTimer.timeoutFlow.collect { event ->
                if (event is HeartbeatTimeoutEvent) {
                    logger.error("SSE: Heartbeat timer expired, closing connection")
                    stopConnection()
                }
            }
        }

        eventFlow.collect { event ->
            handleSseEvent(event)
        }
    }

    private suspend fun handleSseEvent(event: SseEvent) {
        when (event) {
            is ConnectionOpenEvent -> {
                logger.info("SSE: Connection opened, starting heartbeat timer")
                // Start heartbeat timer with default timeout when connection is opened
                heartbeatTimer.startTimer(NetworkUtilities.DEFAULT_HEARTBEAT_TIMEOUT_MS)
            }
            is ServerEvent -> {
                handleServerEvent(event)
            }
        }
    }

    private suspend fun handleServerEvent(event: ServerEvent) {
        when (event.eventType) {
            ServerEvent.CONNECTED -> {
                logger.info("SSE: Connection confirmed")
                // Reset heartbeat timer with default timeout
                heartbeatTimer.startTimer(NetworkUtilities.DEFAULT_HEARTBEAT_TIMEOUT_MS)
            }

            ServerEvent.HEARTBEAT -> {
                logger.debug("SSE: Received heartbeat")
                // Reset heartbeat timer with server timeout + buffer
                val serverTimeout = sseDataParser.parseHeartbeatTimeout(event.data)
                heartbeatTimer.startTimer(serverTimeout + NetworkUtilities.HEARTBEAT_BUFFER_MS)
            }

            ServerEvent.MESSAGES -> {
                try {
                    val messages = sseDataParser.parseMessages(event.data)
                    if (messages.isNotEmpty()) {
                        logger.info("SSE: Received ${messages.size} messages")
                        inAppMessagingManager.dispatch(
                            InAppMessagingAction.ProcessMessageQueue(
                                messages
                            )
                        )
                    } else {
                        logger.debug("SSE: Received empty messages event")
                    }
                } catch (e: Exception) {
                    logger.error("SSE: Failed to parse messages: ${e.message}")
                }
            }

            ServerEvent.TTL_EXCEEDED -> {
                logger.error("SSE: TTL exceeded, connection will be closed")
                // Note: Retry logic will be implemented in Phase 4
            }

            else -> {
                logger.error("SSE: Unknown event type: ${event.eventType}")
            }
        }
    }

    /**
     * Thread-safe method to update connection state.
     * This method handles mutex locking internally.
     */
    private suspend fun updateConnectionState(newState: SseConnectionState) {
        connectionMutex.withLock {
            connectionState = newState
        }
    }
}
