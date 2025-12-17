package io.customer.messaginginapp.gist.data.sse

import android.util.Base64
import io.customer.messaginginapp.gist.data.NetworkUtilities
import io.customer.messaginginapp.state.InAppMessagingManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * SSE service for establishing Server-Sent Events connections.
 *
 * This service handles the actual HTTP connection to the SSE endpoint and provides
 * a flow of SSE events for processing by the connection manager.
 *
 * Thread Safety: This service is designed to be called from thread-safe methods
 * (like SseConnectionManager methods) to avoid unnecessary synchronization overhead.
 * The SseConnectionManager ensures single-threaded access through its mutex.
 */
internal class SseService(
    private val sseLogger: InAppSseLogger,
    private val inAppMessagingManager: InAppMessagingManager
) {

    private var eventSource: EventSource? = null
    private val httpClient = createSseHttpClient()

    /**
     * Connect to SSE endpoint and return a flow of events.
     *
     * This method is NOT thread-safe. It should only be called from thread-safe methods
     * (like SseConnectionManager methods) to avoid unnecessary synchronization overhead.
     */
    fun connectSse(
        sessionId: String,
        userToken: String,
        siteId: String
    ): Flow<SseEvent> = callbackFlow {
        val request = createSseRequest(sessionId, userToken, siteId)

        val currentEventSource = EventSources.createFactory(httpClient)
            .newEventSource(
                request,
                object : EventSourceListener() {

                    override fun onOpen(eventSource: EventSource, response: Response) {
                        sseLogger.logConnectionOpened()
                        val result = trySend(ConnectionOpenEvent)
                        if (!result.isSuccess) {
                            sseLogger.logFailedToSendConnectionOpenedEvent(result.exceptionOrNull()?.message)
                        }
                    }

                    override fun onEvent(
                        eventSource: EventSource,
                        id: String?,
                        type: String?,
                        data: String
                    ) {
                        sseLogger.logReceivedEvent(type)

                        if (type.isNullOrBlank() || data.isBlank()) {
                            sseLogger.logReceivedEventWithNoTypeOrData()
                            return
                        }

                        val result = trySend(ServerEvent(type, data))
                        if (!result.isSuccess) {
                            sseLogger.logFailedToSendEvent(result.exceptionOrNull()?.message)
                        }
                    }

                    override fun onFailure(
                        eventSource: EventSource,
                        t: Throwable?,
                        response: Response?
                    ) {
                        sseLogger.logConnectionFailed(t?.message, response?.code)

                        val sseError = classifySseError(t, response)
                        val result = trySend(ConnectionFailedEvent(sseError))
                        if (!result.isSuccess) {
                            sseLogger.logFailedToSendErrorEvent(result.exceptionOrNull()?.message)
                        }

                        // Close normally - we've already emitted ConnectionFailedEvent, so the collector will handle it
                        // Closing with exception would cause the flow collection to throw, leading to duplicate error handling
                        close()
                    }

                    override fun onClosed(eventSource: EventSource) {
                        sseLogger.logConnectionClosed()

                        val result = trySend(ConnectionClosedEvent)
                        if (!result.isSuccess) {
                            sseLogger.logFailedToSendConnectionClosedEvent()
                        }

                        close()
                    }
                }
            )

        // Update the shared field for disconnect() method, but capture locally for awaitClose
        eventSource = currentEventSource

        awaitClose {
            sseLogger.logFlowCancelled()
            currentEventSource.cancel()
            if (eventSource == currentEventSource) {
                eventSource = null
            }
        }
    }.buffer(Channel.BUFFERED)

    /**
     * Disconnect from SSE endpoint and clean up resources.
     *
     * This method is NOT thread-safe. It should only be called from thread-safe methods
     * (like SseConnectionManager.stopConnection) to avoid unnecessary synchronization overhead.
     */
    fun disconnect() {
        sseLogger.logDisconnectingService()
        eventSource?.cancel()
        eventSource = null
    }

    private fun createSseHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .readTimeout(NetworkUtilities.SSE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val networkRequest = originalRequest.newBuilder()
                val currentState = inAppMessagingManager.getCurrentState()
                NetworkUtilities.addCommonHeaders(networkRequest, currentState, includeUserToken = false) // SSE uses userToken in URL, not header
                networkRequest.addHeader(NetworkUtilities.SSE_ACCEPT_HEADER, NetworkUtilities.SSE_ACCEPT_VALUE)
                networkRequest.addHeader(NetworkUtilities.SSE_CACHE_CONTROL_HEADER, NetworkUtilities.SSE_CACHE_CONTROL_VALUE)
                val finalRequest = networkRequest.build()

                chain.proceed(finalRequest)
            }
            .build()
    }

    private fun createSseRequest(
        sessionId: String,
        userToken: String,
        siteId: String
    ): Request {
        val encodedUserToken = Base64.encodeToString(userToken.toByteArray(), Base64.NO_WRAP)
        val environment = inAppMessagingManager.getCurrentState().environment

        val url = environment.getSseApiUrl().toHttpUrl().newBuilder()
            .addQueryParameter(NetworkUtilities.SSE_SESSION_ID_PARAM, sessionId)
            .addQueryParameter(NetworkUtilities.SSE_SITE_ID_PARAM, siteId)
            .addQueryParameter(NetworkUtilities.SSE_USER_TOKEN_PARAM, encodedUserToken)
            .build()

        sseLogger.logCreatingRequest(url.toString())

        return Request.Builder()
            .url(url)
            .get()
            .build()
    }
}

/**
 * Represents an SSE event from the server connection
 */
internal sealed interface SseEvent

/**
 * Represents a connection opened event (emitted by SseService.onOpen).
 */
internal object ConnectionOpenEvent : SseEvent

/**
 * Represents a server event with type and data.
 *
 * @property eventType The type of event (connected, heartbeat, messages, ttl_exceeded)
 * @property data The JSON data associated with the event
 */
internal data class ServerEvent(
    val eventType: String,
    val data: String
) : SseEvent {
    companion object {
        // Server event types
        const val CONNECTED = "connected"
        const val HEARTBEAT = "heartbeat"
        const val MESSAGES = "messages"
        const val TTL_EXCEEDED = "ttl_exceeded"
    }
}

/**
 * Represents error events that occur during SSE connection or communication.
 */
internal class ConnectionFailedEvent(val error: SseError) : SseEvent

internal object ConnectionClosedEvent : SseEvent
